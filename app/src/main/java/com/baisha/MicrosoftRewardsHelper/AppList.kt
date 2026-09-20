package com.baisha.MicrosoftRewardsHelper

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/** 读取系统应用列表（需要 QUERY_ALL_PACKAGES / 包可见性） */
object AppList {

    data class App(val pkg: String, val label: String) {
        val display: String get() = if (label == pkg) pkg else "$label\n$pkg"
    }

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.QUERY_ALL_PACKAGES) ==
            PackageManager.PERMISSION_GRANTED

    fun load(context: Context): List<App> {
        val pm = context.packageManager
        val apps = mutableListOf<App>()
        runCatching {
            @Suppress("DEPRECATION")
            val packages = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0))
            } else {
                pm.getInstalledPackages(0)
            }
            packages.forEach { info ->
                val pkg = info.packageName ?: return@forEach
                val label = runCatching { info.applicationInfo?.loadLabel(pm)?.toString() ?: pkg }.getOrDefault(pkg)
                apps.add(App(pkg, label))
            }
        }
        return apps.sortedBy { it.label.lowercase() }
    }
}
