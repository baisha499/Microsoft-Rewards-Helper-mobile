package com.baisha.MicrosoftRewardsHelper

import android.graphics.Rect
import java.io.File

/**
 * 截图并根据给定区域判断颜色，用来识别 Day1~Day7 哪些已经变黄（已签入）。
 * 运行在 Shizuku 用户服务进程。
 */
object ScreenAnalyzer {

    private const val SCREEN_FILE = "/data/local/tmp/bing_screen.png"

    /** 判定"蓝底"的最小蓝度：蓝色分量高出红/绿多少 */
    const val BLUE_MIN = 25
    /** 判定"白字"的最小亮度：三通道的最小值 */
    const val WHITE_MIN = 200
    /** 判定"白字"允许的最大色偏（三通道最大差） */
    private const val WHITE_SPREAD_MAX = 40

    /**
     * @param score 区域内最"黄"的像素打分（min(r,g)-b），用于 Day 卡片判定
     * @param r/g/b 区域平均色
     * @param blueHits 采样点里"偏蓝"（蓝底）的点数
     * @param whiteHits 采样点里"接近纯白"（白字）的点数
     */
    data class Sample(
        val score: Int,
        val r: Int,
        val g: Int,
        val b: Int,
        val blueHits: Int = 0,
        val whiteHits: Int = 0
    )

    data class Result(val pngW: Int, val pngH: Int, val samples: List<Sample>)

    /**
     * @param rects  需要取色的区域（uiautomator 坐标系）
     * @param refW   uiautomator 坐标系宽（节点里出现的最大 X）
     * @param refH   uiautomator 坐标系高
     */
    fun analyze(rects: List<Rect>, refW: Int, refH: Int): Result? {
        File(SCREEN_FILE).delete()
        Runtime.getRuntime().exec(arrayOf("sh", "-c", "screencap -p $SCREEN_FILE"))
            .let { p ->
                try {
                    p.waitFor()
                } catch (_: Throwable) {
                }
            }
        var file = File(SCREEN_FILE)
        if (!file.exists()) {
            Runtime.getRuntime().exec(arrayOf("sh", "-c", "screencap $SCREEN_FILE"))
                .let { p -> try { p.waitFor() } catch (_: Throwable) {} }
            file = File(SCREEN_FILE)
        }
        if (!file.exists()) return null
        val image = loadPng(file.absolutePath) ?: return null
        file.delete()

        val samples = rects.map { rect -> sampleRect(image, rect, refW, refH) }
        return Result(image.width, image.height, samples)
    }

    fun loadPng(path: String): PngImage? = try {
        PngReader.decode(File(path).readBytes())
    } catch (_: Throwable) {
        null
    }

    /**
     * 在整张截图里找蓝色块：按固定步长全分辨率扫描（不是粗网格采样，按钮压在格子
     * 边界上也不会漏），先膨胀一圈把白色文字在蓝底上切开的口子补上，再做连通域，
     * 返回每个块的外接矩形（已换算回屏幕 / uiautomator 坐标）。
     */
    fun findBlueBlocks(image: PngImage, refW: Int, refH: Int): List<Rect> {
        val step = (minOf(image.width, image.height) / 360).coerceIn(2, 6)
        val cols = image.width / step
        val rows = image.height / step
        if (cols <= 0 || rows <= 0) return emptyList()

        val blue = ByteArray(cols * rows)
        for (j in 0 until rows) {
            val y = j * step + step / 2
            if (y >= image.height) break
            val rowBase = j * cols
            val pxBase = y * image.width
            for (i in 0 until cols) {
                val x = i * step + step / 2
                if (x >= image.width) break
                val argb = image.pixels[pxBase + x]
                val r = (argb shr 16) and 0xFF
                val g = (argb shr 8) and 0xFF
                val b = argb and 0xFF
                if (b - maxOf(r, g) >= BLUE_MIN) blue[rowBase + i] = 1
            }
        }

        val grown = dilate(blue, cols, rows)
        val boxes = ArrayList<Rect>()
        val visited = BooleanArray(cols * rows)
        val queue = IntArray(cols * rows)
        for (start in 0 until cols * rows) {
            if (grown[start] == ZERO || visited[start]) continue
            var head = 0
            var tail = 0
            queue[tail++] = start
            visited[start] = true
            var minI = Int.MAX_VALUE
            var maxI = Int.MIN_VALUE
            var minJ = Int.MAX_VALUE
            var maxJ = Int.MIN_VALUE
            var count = 0
            while (head < tail) {
                val idx = queue[head++]
                val i = idx % cols
                val j = idx / cols
                if (i < minI) minI = i
                if (i > maxI) maxI = i
                if (j < minJ) minJ = j
                if (j > maxJ) maxJ = j
                count++
                if (i > 0) {
                    val n = idx - 1
                    if (grown[n] != ZERO && !visited[n]) { visited[n] = true; queue[tail++] = n }
                }
                if (i < cols - 1) {
                    val n = idx + 1
                    if (grown[n] != ZERO && !visited[n]) { visited[n] = true; queue[tail++] = n }
                }
                if (j > 0) {
                    val n = idx - cols
                    if (grown[n] != ZERO && !visited[n]) { visited[n] = true; queue[tail++] = n }
                }
                if (j < rows - 1) {
                    val n = idx + cols
                    if (grown[n] != ZERO && !visited[n]) { visited[n] = true; queue[tail++] = n }
                }
            }
            // 太小的当噪点丢掉（按钮至少占屏幕的 0.05%）
            if (count.toLong() * step * step < (refW.toLong() * refH * 0.0005).toLong()) continue
            val box = Rect(minI * step, minJ * step, (maxI + 1) * step, (maxJ + 1) * step)
            boxes.add(toScreenRect(box, image, refW, refH))
        }
        return boxes
    }

    private const val ZERO: Byte = 0

    /** 3x3 膨胀：把白色文字在蓝色按钮上切开的缝隙补回去 */
    private fun dilate(src: ByteArray, cols: Int, rows: Int): ByteArray {
        val out = ByteArray(src.size)
        for (j in 0 until rows) {
            for (i in 0 until cols) {
                if (src[j * cols + i] != ZERO) {
                    out[j * cols + i] = 1
                    continue
                }
                var hit = false
                for (dj in -1..1) {
                    val jj = j + dj
                    if (jj < 0 || jj >= rows) continue
                    for (di in -1..1) {
                        val ii = i + di
                        if (ii < 0 || ii >= cols) continue
                        if (src[jj * cols + ii] != ZERO) {
                            hit = true
                            break
                        }
                    }
                    if (hit) break
                }
                if (hit) out[j * cols + i] = 1
            }
        }
        return out
    }

    /** 截图像素坐标 → 屏幕（uiautomator）坐标，处理 90° 旋转和缩放 */
    private fun toScreenRect(r: Rect, image: PngImage, refW: Int, refH: Int): Rect {
        fun map(px: Int, py: Int): Pair<Int, Int> = when {
            image.width == refW && image.height == refH -> px to py
            image.width == refH && image.height == refW -> py to (refH - 1 - px)
            else -> (px * refW / image.width) to (py * refH / image.height)
        }
        val (x1, y1) = map(r.left, r.top)
        val (x2, y2) = map(r.right, r.bottom)
        return Rect(minOf(x1, x2), minOf(y1, y2), maxOf(x1, x2), maxOf(y1, y2))
    }

    /** 屏幕坐标 → 截图像素坐标，裁剪按钮给 OCR 时用 */
    fun toImageRect(r: Rect, image: PngImage, refW: Int, refH: Int): Rect {
        fun map(x: Int, y: Int): Pair<Int, Int> = when {
            image.width == refW && image.height == refH -> x to y
            image.width == refH && image.height == refW -> (refH - 1 - y) to x
            else -> (x * image.width / refW) to (y * image.height / refH)
        }
        val (x1, y1) = map(r.left, r.top)
        val (x2, y2) = map(r.right, r.bottom)
        return Rect(
            minOf(x1, x2).coerceIn(0, image.width - 1),
            minOf(y1, y2).coerceIn(0, image.height - 1),
            maxOf(x1, x2).coerceIn(1, image.width),
            maxOf(y1, y2).coerceIn(1, image.height)
        )
    }

    private fun sampleRect(image: PngImage, rect: Rect, refW: Int, refH: Int): Sample {
        val map = { x: Int, y: Int ->
            val px: Int
            val py: Int
            when {
                image.width == refW && image.height == refH -> {
                    px = x
                    py = y
                }

                image.width == refH && image.height == refW -> {
                    // 截图与节点坐标相差 90° 旋转
                    px = refH - 1 - y
                    py = x
                }

                else -> {
                    px = x * image.width / refW
                    py = y * image.height / refH
                }
            }
            px to py
        }

        var best = 0
        var sr = 0
        var sg = 0
        var sb = 0
        var count = 0
        var blueHits = 0
        var whiteHits = 0
        // 采样密一点，细笔画的白色文字（"+10"）才有机会被采到
        val steps = 9
        for (i in 0 until steps) {
            for (j in 0 until steps) {
                val x = (rect.left + (rect.width().coerceAtLeast(1) * (i + 0.5) / steps)).toInt()
                val y = (rect.top + (rect.height().coerceAtLeast(1) * (j + 0.5) / steps)).toInt()
                val (px, py) = map(x, y)
                if (px < 0 || py < 0 || px >= image.width || py >= image.height) continue
                val argb = image.pixels[py * image.width + px]
                val r = (argb shr 16) and 0xFF
                val g = (argb shr 8) and 0xFF
                val b = argb and 0xFF
                val score = (minOf(r, g) - b).coerceAtLeast(0)
                if (score > best) best = score
                // 蓝底：蓝色分量明显高于红/绿
                if (b - maxOf(r, g) >= BLUE_MIN) blueHits++
                // 白字：三通道都很亮且彼此接近
                val hi = maxOf(r, g, b)
                val lo = minOf(r, g, b)
                if (lo >= WHITE_MIN && hi - lo <= WHITE_SPREAD_MAX) whiteHits++
                sr += r
                sg += g
                sb += b
                count++
            }
        }
        if (count == 0) return Sample(0, 0, 0, 0)
        return Sample(best, sr / count, sg / count, sb / count, blueHits, whiteHits)
    }

    fun serialize(result: Result): String {
        val body = result.samples.joinToString(";") {
            "${it.score},${it.r},${it.g},${it.b},${it.blueHits},${it.whiteHits}"
        }
        return "${result.pngW},${result.pngH}|$body"
    }

    fun parse(text: String): Result? {
        val (head, body) = text.split("|", limit = 2).takeIf { it.size == 2 } ?: return null
        val wh = head.split(",")
        if (wh.size < 2) return null
        val w = wh[0].trim().toIntOrNull() ?: return null
        val h = wh[1].trim().toIntOrNull() ?: return null
        val samples = if (body.isBlank()) emptyList() else body.split(";").mapNotNull { item ->
            val p = item.split(",").map { it.trim().toIntOrNull() ?: return@mapNotNull null }
            if (p.size < 4) return@mapNotNull null
            Sample(
                p[0], p[1], p[2], p[3],
                blueHits = p.getOrNull(4) ?: 0,
                whiteHits = p.getOrNull(5) ?: 0
            )
        }
        return Result(w, h, samples)
    }
}
