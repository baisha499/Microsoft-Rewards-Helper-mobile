package com.baisha.MicrosoftRewardsHelper

import android.graphics.Rect

/** 区域列表与字符串的互转，用于跨 Binder 传递 */
object RectSpec {

    fun format(rects: List<Rect>): String =
        rects.joinToString(";") { "${it.left},${it.top},${it.right},${it.bottom}" }

    fun parse(raw: String): List<Rect> =
        raw.split(";").mapNotNull { item ->
            val p = item.split(",").map { it.trim().toIntOrNull() ?: return@mapNotNull null }
            if (p.size < 4) return@mapNotNull null
            Rect(p[0], p[1], p[2], p[3])
        }
}
