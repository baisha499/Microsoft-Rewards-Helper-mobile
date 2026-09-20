package com.baisha.MicrosoftRewardsHelper

import android.graphics.Rect
import java.io.File

/**
 * 截图并根据给定区域判断颜色，用来识别 Day1~Day7 哪些已经变黄（已签入）。
 * 运行在 Shizuku 用户服务进程。
 */
object ScreenAnalyzer {

    private const val SCREEN_FILE = "/data/local/tmp/bing_screen.png"

    data class Sample(val score: Int, val r: Int, val g: Int, val b: Int)

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
        val steps = 5
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
                sr += r
                sg += g
                sb += b
                count++
            }
        }
        if (count == 0) return Sample(0, 0, 0, 0)
        return Sample(best, sr / count, sg / count, sb / count)
    }

    fun serialize(result: Result): String {
        val body = result.samples.joinToString(";") { "${it.score},${it.r},${it.g},${it.b}" }
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
            Sample(p[0], p[1], p[2], p[3])
        }
        return Result(w, h, samples)
    }
}
