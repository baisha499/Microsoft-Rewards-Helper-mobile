package com.baisha.MicrosoftRewardsHelper

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 绑定 Shizuku 用户服务：把「在 shell 身份进程里跑 shell」的能力拿到应用进程。
 */
object UserShell {

    private const val TAG = "bing_shell"
    private const val VERSION = 1
    private const val WAIT_MS = 8_000L
    /** 绑定失败后的冷却时间，避免每条 shell 命令都卡满重试 */
    private const val BIND_COOLDOWN_MS = 30_000L

    private val lock = Any()

    @Volatile
    private var instance: Proxy? = null

    /** 在这个时间点之前不再尝试绑定（绑定刚失败过） */
    @Volatile
    private var nextBindAt = 0L

    /**
     * 传给 Shizuku 的服务代码版本。Shizuku 发现版本变了才会重建用户服务进程；
     * 每次绑定失败就 +1，避免它一直拿着那个"存在但起不来"的旧服务不放。
     */
    @Volatile
    private var serviceVersion = VERSION

    /** tag 后缀序号：0 表示用原 tag，非 0 时另起一个服务，绕开杀不掉的旧进程 */
    @Volatile
    private var tagSeq = 0

    @Volatile
    private var signal: CountDownLatch? = null

    /** 是否已「释放」：释放期间不绑定、不执行任何 shell */
    @Volatile
    private var released = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            instance = binder?.let { Proxy(it) }
            signal?.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            instance = null
        }
    }

    fun isReleased(): Boolean = released

    /** 只改标记，不做 Binder 调用（可在主线程调用） */
    fun setReleasedFlag(value: Boolean) {
        released = value
    }

    /**
     * 释放 / 恢复自动化：释放时立刻解绑 Shizuku 用户服务（shell 进程退出），
     * 之后 get() 一律返回 null，所有 shell 调用自动跳过；恢复后下次用到时重新绑定。
     * 建议在非主线程调用。
     */
    fun setReleased(context: Context, value: Boolean) {
        released = value
        if (value) {
            release(context)
        } else {
            // 恢复后立刻允许重新绑定，不用等冷却
            nextBindAt = 0L
        }
    }

    /** 获取可用实例，必要时发起绑定并等待；失败会重试一次 */
    fun get(context: Context): Proxy? {
        if (released) return null
        alive()?.let { return it }
        if (!Shell.hasPermission()) return null
        // ServiceConnection 的回调在主线程投递，所以这里绝不能在主线程阻塞等待，
        // 否则 onServiceConnected 永远等不到，直接 ANR
        val onMainThread = android.os.Looper.myLooper() == android.os.Looper.getMainLooper()

        // 刚失败过就别再死等了，否则每次 exec 都要卡满重试时间
        if (instance == null && System.currentTimeMillis() < nextBindAt) return null

        var result: Proxy? = null
        synchronized(lock) {
            result = alive()
            if (result == null) {
                val args = buildArgs(context)
                repeat(2) { attempt ->
                    // 第二次之前先清掉残留服务，很多"一直不回调"就是残留进程卡着
                    if (attempt > 0) {
                        runCatching { Shizuku.unbindUserService(args, null, true) }
                        instance = null
                    }
                    bindOnce(args, wait = !onMainThread)
                    result = alive()
                    if (result != null) return@repeat
                }
            }
        }
        if (result != null) {
            nextBindAt = 0L
        } else {
            nextBindAt = System.currentTimeMillis() + BIND_COOLDOWN_MS
            // 下次换个版本号，让 Shizuku 重建服务而不是复用那个起不来的
            serviceVersion++
        }
        return result
    }

    /**
     * 清掉可能残留的旧用户服务进程，并允许立刻重新绑定。
     * 残留服务会让新的 bindUserService 一直不回调——服务进程卡在旧代码上，
     * Shizuku 就拉不起新的。
     */
    fun kick(context: Context) {
        runCatching { Shizuku.unbindUserService(buildArgs(context), null, true) }
        instance = null
        nextBindAt = 0L
        // 换 service version 强迫 Shizuku 重建；旧进程若是旧版 APK 起的（没实现 destroy）
        // 杀不掉，再换个 tag，让它当作全新服务另起一个
        serviceVersion++
        tagSeq++
    }

    private fun buildArgs(context: Context): Shizuku.UserServiceArgs =
        Shizuku.UserServiceArgs(
            ComponentName(context.packageName, ShellUserService::class.java.name)
        )
            .processNameSuffix(":shell")
            .tag(if (tagSeq == 0) TAG else "${TAG}_$tagSeq")
            .version(serviceVersion)
            .daemon(false)

    /** 发起一次绑定；wait=false 时不等回调（主线程用） */
    private fun bindOnce(args: Shizuku.UserServiceArgs, wait: Boolean): Boolean {
        val latch = CountDownLatch(1)
        signal = latch
        return try {
            Shizuku.bindUserService(args, connection)
            if (wait) runCatching { latch.await(WAIT_MS, TimeUnit.MILLISECONDS) }
            true
        } catch (_: Throwable) {
            false
        } finally {
            signal = null
        }
    }

    fun release(context: Context) {
        val args = Shizuku.UserServiceArgs(
            ComponentName(context.packageName, ShellUserService::class.java.name)
        )
            .processNameSuffix(":shell")
            .tag(TAG)
            .version(VERSION)
            .daemon(false)
        runCatching { Shizuku.unbindUserService(args, connection, false) }
        instance = null
    }

    private fun alive(): Proxy? {
        val proxy = instance ?: return null
        val ok = runCatching { proxy.ping() }.getOrDefault(false)
        if (!ok) {
            // 服务进程被杀 / binder 断了，下次 get() 会重新绑定
            instance = null
            return null
        }
        return proxy
    }

    /** 用户服务远程调用代理 */
    class Proxy(private val binder: IBinder) {

        fun ping(): Boolean = binder.pingBinder()

        fun exec(cmd: String, timeoutMs: Int): String? =
            call(UserShellProtocol.EXEC) {
                writeString(cmd)
                writeInt(timeoutMs)
            }

        fun dumpNodes(timeoutMs: Int): String? =
            call(UserShellProtocol.DUMP_NODES) {
                writeInt(timeoutMs)
            }

        /** 执行并返回 stdout+stderr，用于诊断 */
        fun execAll(cmd: String, timeoutMs: Int = 15_000): String? =
            call(UserShellProtocol.EXEC_ALL) {
                writeString(cmd)
                writeInt(timeoutMs)
            }

        /** 对若干区域取色，用于判断 Day 卡片是否变黄 */
        fun analyze(rectsRaw: String, refW: Int, refH: Int, timeoutMs: Int = 20_000): String? =
            call(UserShellProtocol.ANALYZE) {
                writeString(rectsRaw)
                writeInt(refW)
                writeInt(refH)
                writeInt(timeoutMs)
            }

        /** 截屏保存到指定路径，成功返回该路径 */
        fun capture(path: String, timeoutMs: Int = 20_000): String? =
            call(UserShellProtocol.CAPTURE) {
                writeString(path)
                writeInt(timeoutMs)
            }

        private fun call(code: Int, writer: Parcel.() -> Unit): String? {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            return try {
                data.writeInterfaceToken(UserShellProtocol.DESCRIPTOR)
                data.writer()
                binder.transact(code, data, reply, 0)
                reply.readException()
                reply.readString()
            } catch (e: Throwable) {
                null
            } finally {
                data.recycle()
                reply.recycle()
            }
        }
    }
}
