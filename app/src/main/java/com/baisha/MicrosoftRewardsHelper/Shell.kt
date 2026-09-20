package com.baisha.MicrosoftRewardsHelper

import android.content.Context
import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

/**
 * 通过 Shizuku 用户服务执行 shell 命令。
 */
object Shell {

    data class Result(val out: String, val err: String)

    fun isBinderAlive(): Boolean = try {
        Shizuku.pingBinder()
    } catch (_: Throwable) {
        false
    }

    fun hasPermission(): Boolean = try {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) {
        false
    }

    fun exec(context: Context, cmd: String, timeoutMs: Int = 15_000): Result {
        val svc = UserShell.get(context) ?: return Result("", "用户服务未连接")
        return try {
            Result(svc.exec(cmd, timeoutMs) ?: "", "")
        } catch (e: Throwable) {
            Result("", e.message ?: "执行异常")
        }
    }
}
