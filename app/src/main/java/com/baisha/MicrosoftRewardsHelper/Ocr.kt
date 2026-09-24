package com.baisha.MicrosoftRewardsHelper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/**
 * 蓝色按钮上的文字识别。
 * 用的是 ML Kit 的 bundled 模型（打包进 APK），运行时不需要联网、不需要 Google Play 服务。
 */
object Ocr {

    private val client by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    /** 识别一块图片里的文字，失败返回空串（识别在 IO 线程，不卡界面） */
    suspend fun text(bitmap: Bitmap): String = withContext(Dispatchers.IO) {
        try {
            val result = client.process(InputImage.fromBitmap(bitmap, 0)).await()
            result?.text?.replace('\n', ' ')?.trim().orEmpty()
        } catch (_: Throwable) {
            ""
        }
    }

    private suspend fun com.google.android.gms.tasks.Task<Text>.await(): Text? =
        suspendCancellableCoroutine<Text?> { cont ->
            addOnSuccessListener { if (cont.isActive) cont.resume(it) }
            addOnFailureListener { if (cont.isActive) cont.resume(null) }
            addOnCanceledListener { if (cont.isActive) cont.resume(null) }
        }
}

/**
 * OCR 缓存：每次识别到的蓝色按钮都裁成小图存到应用缓存目录，并记下识别出的文字。
 * 目录在 `cacheDir/ocr_buttons`，点设置里的「清理缓存」会一起清掉。
 */
object OcrCache {

    private const val DIR = "ocr_buttons"

    fun dir(context: Context): File = File(context.cacheDir, DIR)

    /** 每次任务开始前清空，避免缓存越攒越多 */
    fun reset(context: Context) {
        runCatching { dir(context).deleteRecursively() }
    }

    /**
     * 从截图中裁出一块（rect 是屏幕坐标，内部会换算成截图像素坐标；
     * 截图是 PngReader 解出来的 ARGB 像素）
     */
    fun crop(image: PngImage, rect: Rect, refW: Int, refH: Int): Bitmap? {
        val box = ScreenAnalyzer.toImageRect(rect, image, refW, refH)
        val l = box.left.coerceIn(0, image.width - 1)
        val t = box.top.coerceIn(0, image.height - 1)
        val r = box.right.coerceIn(l + 1, image.width)
        val b = box.bottom.coerceIn(t + 1, image.height)
        val w = r - l
        val h = b - t
        if (w <= 0 || h <= 0) return null
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            val src = (t + y) * image.width + l
            System.arraycopy(image.pixels, src, pixels, y * w, w)
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, w, 0, 0, w, h)
        }
    }

    /** 保存裁剪图和一行 OCR 记录 */
    fun save(context: Context, index: Int, rect: Rect, bitmap: Bitmap, text: String) {
        runCatching {
            val dir = dir(context).apply { mkdirs() }
            val file = File(dir, "btn_${System.currentTimeMillis()}_$index.png")
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            File(dir, "ocr_log.txt").appendText(
                "[$index] [${rect.left},${rect.top}][${rect.right},${rect.bottom}] " +
                    "${rect.width()}x${rect.height()} 「$text」 ${file.name}\n"
            )
        }
    }
}
