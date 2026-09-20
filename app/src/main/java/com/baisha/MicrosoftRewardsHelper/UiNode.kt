package com.baisha.MicrosoftRewardsHelper

import android.graphics.Rect

data class UiNode(
    val text: String,
    val desc: String,
    val bounds: Rect
) {
    val label: String get() = if (text.isNotEmpty()) text else desc
    val centerX: Int get() = bounds.centerX()
    val centerY: Int get() = bounds.centerY()
}
