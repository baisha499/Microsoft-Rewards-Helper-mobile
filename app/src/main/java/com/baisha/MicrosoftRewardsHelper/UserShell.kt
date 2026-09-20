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

    private val lock = Any()

    @Volatile
    private var instance: Proxy? = null

    @Volatile
    private var signal: CountDownLatch? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            instance = binder?.let { Proxy(it) }
            signal?.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            instance = null
        }
    }

    /** 获取可用实例，必要时发起绑定并等待 */
    fun get(context: Context): Proxy? {
        alive()?.let { return it }
        if (!Shell.hasPermission()) return null
        // ServiceConnection 的回调在主线程投递，所以这里绝不能在主线程阻塞等待，
        // 否则 onServiceConnected 永远等不到，直接 ANR
        val onMainThread = android.os.Looper.myLooper() == android.os.Looper.getMainLooper()

        synchronized(lock) {
            alive()?.let { return it }
            val latch = CountDownLatch(1)
            signal = latch
            val args = Shizuku.UserServiceArgs(
                ComponentName(context.packageName, ShellUserService::class.java.name)
            )
                .processNameSuffix(":shell")
                .tag(TAG)
                .version(VERSION)
                .daemon(false)
            try {
                Shizuku.bindUserService(args, connection)
            } catch (e: Throwable) {
                signal = null
                return null
            }
            if (onMainThread) {
                // 主线程：只发起绑定，不等待，调用方稍后重试即可
                return null
            }
            runCatching { latch.await(WAIT_MS, TimeUnit.MILLISECONDS) }
        }
        return alive()
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
        return runCatching {
            if (proxy.ping()) proxy else null
        }.getOrNull()
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
