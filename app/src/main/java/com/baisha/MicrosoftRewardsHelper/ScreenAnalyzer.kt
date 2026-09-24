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
        val image = try {
            PngReader.decode(file.readBytes())
        } catch (_: Throwable) {
            null
        } ?: return null
        file.delete()

        val samples = rects.map { rect -> sampleRect(image, rect, refW, refH) }
        return Result(image.width, image.height, samples)
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
