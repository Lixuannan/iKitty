package com.codingcow.ikitty

/**
 * 注入 system prompt 的「此刻」背景。
 *
 * 时间和位置都是**易变信息**，所以要放在提示词的最后一块，而不是塞进每条历史消息：
 * 历史消息一旦带上会变的时间戳，每轮请求的字节都不一样，服务商的提示词缓存就全失效了。
 */
object AmbientContext {

    fun block(now: Long, lastMessageAt: Long?, place: Place?): String = buildString {
        appendLine("【此刻】")
        appendLine("- 现在：${formatMoment(now)}")
        lastMessageAt?.let { appendLine("- 距离上一条消息：${formatElapsed(now - it)}") }
        if (place != null && !place.isEmpty) {
            appendLine("- 主人大致在：${place.display}（按网络 IP 推测，只到城市，可能不准）")
        }
        appendLine()
        append("这些只是背景。自然地用，不要复述这几行，也不要假装知道具体的地址。")
    }
}
