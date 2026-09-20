package com.baisha.MicrosoftRewardsHelper

import android.graphics.Rect

/**
 * uiautomator dump 的节点摘要：
 * 用户服务进程负责把庞大的 XML 抽成「text ␁ content-desc ␁ bounds」的行，
 * 这里再把它还原成 UiNode 列表。
 */
object NodeSummary {

    const val SEP = '\u0001'

    private val NODE_REGEX = Regex("<node\\b[^>]*>")
    private val BOUNDS_REGEX = Regex("\\[(-?\\d+),(-?\\d+)]\\[(-?\\d+),(-?\\d+)]")

    fun extract(xml: String): String {
        val sb = StringBuilder()
        NODE_REGEX.findAll(xml).forEach { m ->
            val tag = m.value
            val bounds = BOUNDS_REGEX.find(attr(tag, "bounds"))?.value ?: return@forEach
            sb.append(decode(attr(tag, "text"))).append(SEP)
                .append(decode(attr(tag, "content-desc"))).append(SEP)
                .append(bounds).append('\n')
        }
        return sb.toString()
    }

    fun parse(summary: String): List<UiNode> {
        if (summary.isEmpty()) return emptyList()
        val nodes = ArrayList<UiNode>(128)
        summary.lineSequence().forEach { line ->
            val parts = line.split(SEP)
            if (parts.size < 3) return@forEach
            val rect = parseBounds(parts[2]) ?: return@forEach
            nodes.add(
                UiNode(
                    text = parts[0],
                    desc = parts[1],
                    bounds = rect
                )
            )
        }
        return nodes
    }

    private fun parseBounds(raw: String): Rect? {
        val m = BOUNDS_REGEX.find(raw) ?: return null
        val (x1, y1, x2, y2) = m.destructured
        val l = x1.toIntOrNull() ?: return null
        val t = y1.toIntOrNull() ?: return null
        val r = x2.toIntOrNull() ?: return null
        val b = y2.toIntOrNull() ?: return null
        if (r < l || b < t) return null
        return Rect(l, t, r, b)
    }

    private fun attr(tag: String, name: String): String {
        val idx = tag.indexOf("$name=\"")
        if (idx < 0) return ""
        val start = idx + name.length + 2
        val end = tag.indexOf('"', start)
        if (end < 0) return ""
        return tag.substring(start, end)
    }

    private fun decode(s: String): String = s
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")
}
