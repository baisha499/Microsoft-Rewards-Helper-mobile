package com.baisha.MicrosoftRewardsHelper

import android.os.Binder
import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * 运行在 Shizuku 用户服务进程里的实现。
 * 该进程具备 root / shell 身份，这里的 Runtime.exec 等价于 adb shell。
 *
 * Binder 单次事务上限约 1MB，所以 dump 结果只返回抽取后的节点摘要。
 */
class ShellUserService : Binder(), IInterface {

    companion object {
        private const val DUMP_FILE = "/data/local/tmp/bing_auto.xml"
        private const val DUMP_FILE_FALLBACK = "/sdcard/bing_auto.xml"
    }

    init {
        attachInterface(this, UserShellProtocol.DESCRIPTOR)
    }

    override fun asBinder(): IBinder = this

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        when (code) {
            UserShellProtocol.EXEC -> {
                data.enforceInterface(UserShellProtocol.DESCRIPTOR)
                val cmd = data.readString() ?: ""
                val timeoutMs = data.readInt()
                val result = exec(cmd, timeoutMs)
                reply?.writeNoException()
                reply?.writeString(result)
                return true
            }

            UserShellProtocol.DUMP_NODES -> {
                data.enforceInterface(UserShellProtocol.DESCRIPTOR)
                val timeoutMs = data.readInt()
                val result = dumpNodes(timeoutMs)
                reply?.writeNoException()
                reply?.writeString(result)
                return true
            }

            UserShellProtocol.EXEC_ALL -> {
                data.enforceInterface(UserShellProtocol.DESCRIPTOR)
                val cmd = data.readString() ?: ""
                val timeoutMs = data.readInt()
                val result = execAll(cmd, timeoutMs)
                reply?.writeNoException()
                reply?.writeString(result)
                return true
            }

            UserShellProtocol.ANALYZE -> {
                data.enforceInterface(UserShellProtocol.DESCRIPTOR)
                val rectsRaw = data.readString() ?: ""
                val refW = data.readInt()
                val refH = data.readInt()
                val timeoutMs = data.readInt()
                val result = analyze(rectsRaw, refW, refH, timeoutMs)
                reply?.writeNoException()
                reply?.writeString(result)
                return true
            }

            UserShellProtocol.CAPTURE -> {
                data.enforceInterface(UserShellProtocol.DESCRIPTOR)
                val path = data.readString() ?: ""
                val timeoutMs = data.readInt()
                val ok = capture(path, timeoutMs)
                reply?.writeNoException()
                reply?.writeString(if (ok) path else "")
                return true
            }
        }
        return super.onTransact(code, data, reply, flags)
    }

    fun exec(cmd: String, timeoutMs: Int): String = run(cmd, timeoutMs, includeErr = false)

    /** 同时返回 stdout + stderr，用于排查 uiautomator 失败原因 */
    fun execAll(cmd: String, timeoutMs: Int): String = run(cmd, timeoutMs, includeErr = true)

    private fun run(cmd: String, timeoutMs: Int, includeErr: Boolean): String {
        val timeout = timeoutMs.toLong().coerceIn(1_000L, 120_000L)
        val process = try {
            Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
        } catch (_: Throwable) {
            return ""
        }
        val out = StringBuilder()
        val finished = CountDownLatch(1)
        thread(name = "us-out") {
            try {
                process.inputStream.bufferedReader().forEachLine { out.append(it).append('\n') }
            } catch (_: Throwable) {
            }
        }
        thread(name = "us-err") {
            try {
                val sink = if (includeErr) out else StringBuilder()
                process.errorStream.bufferedReader().forEachLine { sink.append(it).append('\n') }
            } catch (_: Throwable) {
            }
        }
        thread(name = "us-wait") {
            try {
                process.waitFor()
            } catch (_: Throwable) {
            } finally {
                finished.countDown()
            }
        }

        finished.await(timeout, TimeUnit.MILLISECONDS)
        if (finished.count > 0) {
            runCatching { process.destroy() }
            runCatching { Thread.sleep(120) }
        }
        return out.toString()
    }

    /**
     * 多策略 dump：
     * 1) uiautomator dump → /data/local/tmp
     * 2) uiautomator dump --compressed（部分机型/版本只有这个能成功）
     * 3) uiautomator dump 直接输出到 stdout
     * 4) 回退到 /sdcard
     */
    fun dumpNodes(timeoutMs: Int): String {
        val xml = StringBuilder()

        fun tryFile(file: String, compressed: Boolean) {
            val flag = if (compressed) " --compressed " else " "
            exec("rm -f $file; uiautomator dump$flag$file >/dev/null 2>&1", 25_000)
            xml.setLength(0)
            xml.append(read(file))
            runCatching { File(file).delete() }
        }

        tryFile(DUMP_FILE, compressed = false)
        if (!xml.contains("<node")) tryFile(DUMP_FILE, compressed = true)
        if (!xml.contains("<node")) {
            xml.setLength(0)
            xml.append(exec("uiautomator dump 2>/dev/null", 25_000))
        }
        if (!xml.contains("<node")) tryFile(DUMP_FILE_FALLBACK, compressed = false)

        val text = xml.toString()
        if (!text.contains("<node")) return ""
        return NodeSummary.extract(text)
    }

    private fun read(path: String): String = try {
        File(path).readText()
    } catch (_: Throwable) {
        ""
    }

    /** screencap 后对给定区域取色，返回序列化结果 */
    fun analyze(rectsRaw: String, refW: Int, refH: Int, timeoutMs: Int): String {
        val rects = RectSpec.parse(rectsRaw)
        if (rects.isEmpty()) return ""
        val result = ScreenAnalyzer.analyze(rects, refW, refH) ?: return ""
        return ScreenAnalyzer.serialize(result)
    }

    /**
     * 截屏并保存到指定路径（应用传的是自己的 externalCacheDir，shell 能写、应用能读）。
     * /data/local/tmp 应用读不到，所以必须写到应用的外部缓存目录。
     */
    fun capture(path: String, timeoutMs: Int): Boolean {
        if (path.isBlank()) return false
        val file = File(path)
        runCatching { file.parentFile?.mkdirs() }
        runCatching { file.delete() }
        exec("screencap -p $path", timeoutMs)
        if (!file.exists()) exec("screencap $path", timeoutMs)
        return file.exists() && file.length() > 0
    }

    private fun exists(path: String): Boolean = try {
        File(path).exists()
    } catch (_: Throwable) {
        false
    }
}
