package com.baisha.MicrosoftRewardsHelper

import java.io.ByteArrayOutputStream
import java.util.zip.Inflater

class PngImage(val width: Int, val height: Int, val pixels: IntArray)

/**
 * 极简 PNG 解码（8bit / 非隔行 / RGB 或 RGBA）。
 * 用于在 Shizuku 用户服务进程里分析截图像素，Android 上没有 ImageIO 可用。
 */
object PngReader {

    private val SIG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)

    fun decode(bytes: ByteArray): PngImage? {
        if (bytes.size < 33) return null
        for (i in 0..3) {
            if (bytes[i] != SIG[i]) return null
        }
        var pos = 8
        var width = 0
        var height = 0
        var bitDepth = 0
        var colorType = -1
        var interlace = 0
        val idat = ByteArrayOutputStream()

        while (pos + 8 <= bytes.size) {
            val len = readInt(bytes, pos)
            val type = String(bytes, pos + 4, 4, Charsets.US_ASCII)
            val start = pos + 8
            if (start + len + 4 > bytes.size) return null
            when (type) {
                "IHDR" -> {
                    width = readInt(bytes, start)
                    height = readInt(bytes, start + 4)
                    bitDepth = bytes[start + 8].toInt() and 0xFF
                    colorType = bytes[start + 9].toInt() and 0xFF
                    interlace = bytes[start + 12].toInt() and 0xFF
                }

                "IDAT" -> idat.write(bytes, start, len)
                "IEND" -> break
            }
            pos = start + len + 4
        }

        if (width <= 0 || height <= 0) return null
        if (bitDepth != 8 || interlace != 0) return null
        val channels = when (colorType) {
            0 -> 1
            2 -> 3
            4 -> 2
            6 -> 4
            else -> return null
        }

        val stride = width * channels
        val raw = ByteArray((stride + 1) * height)
        val inflater = Inflater()
        try {
            inflater.setInput(idat.toByteArray())
            val n = inflater.inflate(raw)
            if (n < raw.size) return null
        } catch (_: Throwable) {
            return null
        } finally {
            inflater.end()
        }

        val out = IntArray(width * height)
        var prev = ByteArray(stride)
        var rp = 0
        for (y in 0 until height) {
            val filter = raw[rp].toInt() and 0xFF
            rp++
            val line = ByteArray(stride)
            System.arraycopy(raw, rp, line, 0, stride)
            rp += stride
            unfilter(filter, line, prev, channels)
            var i = 0
            for (x in 0 until width) {
                val argb = when (channels) {
                    4 -> argb(line[i].toInt() and 0xFF, line[i + 1].toInt() and 0xFF, line[i + 2].toInt() and 0xFF)
                    3 -> argb(line[i].toInt() and 0xFF, line[i + 1].toInt() and 0xFF, line[i + 2].toInt() and 0xFF)
                    2 -> {
                        val v = line[i].toInt() and 0xFF
                        argb(v, v, v)
                    }
                    else -> {
                        val v = line[i].toInt() and 0xFF
                        argb(v, v, v)
                    }
                }
                out[y * width + x] = argb
                i += channels
            }
            prev = line
        }
        return PngImage(width, height, out)
    }

    private fun argb(r: Int, g: Int, b: Int): Int =
        0xFF000000.toInt() or (r shl 16) or (g shl 8) or b

    private fun readInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    private fun unfilter(filter: Int, line: ByteArray, prev: ByteArray, channels: Int) {
        val size = line.size
        for (i in 0 until size) {
            val a = if (i >= channels) line[i - channels].toInt() and 0xFF else 0
            val b = prev[i].toInt() and 0xFF
            val c = if (i >= channels) prev[i - channels].toInt() and 0xFF else 0
            val x = line[i].toInt() and 0xFF
            val value = when (filter) {
                1 -> x + a
                2 -> x + b
                3 -> x + ((a + b) shr 1)
                4 -> x + paeth(a, b, c)
                else -> x
            }
            line[i] = (value and 0xFF).toByte()
        }
    }

    private fun paeth(a: Int, b: Int, c: Int): Int {
        val p = a + b - c
        val pa = kotlin.math.abs(p - a)
        val pb = kotlin.math.abs(p - b)
        val pc = kotlin.math.abs(p - c)
        return if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
    }
}
