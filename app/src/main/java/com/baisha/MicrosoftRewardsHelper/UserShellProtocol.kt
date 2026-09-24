package com.baisha.MicrosoftRewardsHelper

import android.os.IBinder

/**
 * 与 Shizuku 用户服务之间的 IPC 约定。
 * 手写 transact 而不用 AIDL，避免本机 AIDL 编译环境的坑。
 */
object UserShellProtocol {

    const val DESCRIPTOR = "com.baisha.MicrosoftRewardsHelper.IUserShell"

    const val EXEC = IBinder.FIRST_CALL_TRANSACTION
    const val DUMP_NODES = IBinder.FIRST_CALL_TRANSACTION + 1
    const val ANALYZE = IBinder.FIRST_CALL_TRANSACTION + 2
    const val EXEC_ALL = IBinder.FIRST_CALL_TRANSACTION + 3
    const val CAPTURE = IBinder.FIRST_CALL_TRANSACTION + 4
}
