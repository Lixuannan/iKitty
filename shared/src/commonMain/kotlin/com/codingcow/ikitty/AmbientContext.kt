package com.codingcow.ikitty

/**
 * 注入请求的「此刻」背景。
 *
 * 时间和位置都是**易变信息**，所以「现在几点」不写进每条历史消息：那条信息每轮都变，
 * 写进历史会让请求字节逐轮不同，服务商的提示词缓存就全失效了。
 *
 * 但历史消息各自带上**绝对**时间前缀（见 `StoredMessage.contentForPrompt`）不受这条约束：
 * 一条消息是什么时候说的，落盘那一刻就定死了，之后每轮都不一样的是「现在」，不是它。
 *
 * 光把「现在」塞进 system 提示词也不够。实测的失效模式：长对话里模型更愿意沿用几轮前
 * 自己说过的时间，而不是 system 里那一行「现在」，于是会答出过期的时间。原因是这段文字
 * 离它要生成的那句话太远，注意力被对话本身盖过。
 *
 * 所以 [block] 由 [ContextAssembler] 放在**历史之后的最后一条 system 消息**里：离生成点最近，
 * 又完全不进历史，缓存前缀照样稳定。
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
        appendLine("历史里每条消息开头的方括号是那条消息发出的时间，可以用它判断先后和隔了多久。")
        append(
            "这一行「现在」是唯一权威的当前时间，每次说话都会刷新。" +
                "涉及时间、日期、星期、早上还是晚上、隔了多久的问题，都以此为准；" +
                "不要沿用你之前说过的时间，也不要靠猜。" +
                "这些只是背景，自然地用，不要复述这几行，也不要假装知道具体的地址。"
        )
    }
}
