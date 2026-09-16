package com.codingcow.ikitty

import kotlin.math.min

/**
 * 一次上下文装配的结果。
 *
 * [messages] 已经是可以直接发给服务商的形式；其余字段用来向用户解释
 * "这次为什么只带了这几条"。
 */
data class ContextPlan(
    val messages: List<ChatMessage>,
    val keptMessages: Int,
    val droppedMessages: Int,
    val estimatedTokens: Int,
    val inputBudget: Int
)

/**
 * token 估算。
 *
 * 刻意不引 tokenizer：装配只需要"不超预算"，于是宁可高估。
 * 真实用量由服务端返回的 usage 校准（设置页的测试连接会展示）。
 */
object TokenEstimator {

    /** 每条消息的固定开销：role、分隔符等。 */
    private const val MESSAGE_OVERHEAD = 4

    /**
     * 每张图片的粗估 token。
     *
     * 服务端按图片分辨率计算真实用量，这里只取一个偏高的固定值：估算只需要保证"不超预算"，
     * 精确值看服务端返回的 usage。
     */
    const val IMAGE_TOKENS = 1100

    fun estimateText(text: String): Int {
        if (text.isEmpty()) return 0
        var wide = 0
        var narrow = 0
        text.forEach { char -> if (char.isWide()) wide++ else narrow++ }
        // 中文大致 1 token/字，英文大致 4 字符/token。
        return wide + (narrow + 3) / 4
    }

    fun estimateMessage(content: String, imageCount: Int = 0): Int =
        estimateText(content) + MESSAGE_OVERHEAD + imageCount * IMAGE_TOKENS

    private fun Char.isWide(): Boolean {
        val code = code
        return code in 0x1100..0x115F ||   // 韩文字母
            code in 0x2E80..0x303F ||      // 部首、CJK 标点
            code in 0x3040..0x30FF ||      // 假名
            code in 0x4E00..0x9FFF ||      // CJK 统一表意文字
            code in 0xAC00..0xD7AF ||      // 韩文音节
            code in 0xF900..0xFAFF ||      // CJK 兼容表意文字
            code in 0xFF00..0xFF60 ||      // 全角
            code in 0xFFE0..0xFFE6
    }
}

/**
 * 上下文装配。
 *
 * 这是"哪些消息进请求"的唯一决策点，取代原先的 `takeLast(HISTORY_LIMIT)`：
 * 按 token 预算从最近往前装，裁剪以**轮**为单位，绝不切开一问一答。
 */
object ContextAssembler {

    /** 估算误差的安全余量。 */
    const val SAFETY_TOKENS = 512

    /** 输出至少留这么多，避免 max_tokens 设成 0 时把输入顶满。 */
    const val MIN_REPLY_RESERVE = 1024

    /** 无论如何都保留的输入预算下限。 */
    const val MIN_INPUT_BUDGET = 1024

    /** 这次请求最多能装多少输入 token。窗口大小来自 [ModelSpec.contextWindow]。 */
    fun budgetFor(spec: ModelSpec, maxTokens: Int): Int {
        val requested = if (maxTokens > 0) {
            spec.maxTokens?.snap(maxTokens.toFloat())?.toInt() ?: maxTokens
        } else {
            0
        }
        // 输出预留最多占窗口一半：用户把 max_tokens 设得比窗口还大时，
        // 不这么夹一下就会算出负数预算。
        val reserve = min(maxOf(requested, MIN_REPLY_RESERVE), spec.contextWindow / 2)
        return (spec.contextWindow - reserve - SAFETY_TOKENS).coerceAtLeast(MIN_INPUT_BUDGET)
    }

    fun assemble(
        systemPrompt: String,
        memoryBlock: String,
        ambientBlock: String,
        history: List<StoredMessage>,
        budget: Int,
        /**
         * 把 [StoredMessage.images] 里的本机文件名解析成数据 URL。
         *
         * 装配本身保持纯函数：调用方先在 IO 线程把需要的图片编码好，这里只做查表，
         * 于是长对话重发历史图片不会阻塞主线程。
         */
        imageUrl: (String) -> String? = { null }
    ): ContextPlan {
        // 稳定的在前、易变的在后：这样提示词缓存的前缀能尽量复用。
        val system = buildString {
            append(systemPrompt.trimEnd())
            listOf(memoryBlock, ambientBlock).forEach { block ->
                if (block.isNotBlank()) {
                    append("\n\n")
                    append(block.trimEnd())
                }
            }
        }
        val systemTokens = TokenEstimator.estimateMessage(system)
        val usable = history.filter { !it.localError }
        val units = buildUnits(usable)

        var used = 0
        var start = units.size
        if (units.isNotEmpty()) {
            val costs = units.map { unit ->
                unit.sumOf { TokenEstimator.estimateMessage(it.content, it.images.size) }
            }
            // 至少保留最后一轮：宁可让服务端报上下文超长，
            // 也不要发一条只有 system、没有 user 的请求。
            start = units.lastIndex
            used = costs[start]
            var index = units.lastIndex - 1
            while (index >= 0 && used + costs[index] <= budget - systemTokens) {
                used += costs[index]
                start = index
                index--
            }
        }

        // 请求不能以 assistant 开头，所以丢掉领先的 assistant 消息（例如开场白）。
        val kept = units.subList(start, units.size).flatten()
            .dropWhile { it.role != StoredMessage.ROLE_USER }

        val messages = buildList {
            add(ChatMessage("system", system))
            kept.forEach { add(it.toWire(imageUrl)) }
        }
        return ContextPlan(
            messages = messages,
            keptMessages = kept.size,
            droppedMessages = usable.size - kept.size,
            estimatedTokens = systemTokens + used,
            inputBudget = budget
        )
    }

    /** 按"轮"分组：一条 user 及其之后的 assistant 回复算一轮。 */
    private fun buildUnits(history: List<StoredMessage>): List<List<StoredMessage>> {
        val units = mutableListOf<MutableList<StoredMessage>>()
        history.forEach { message ->
            if (message.role == StoredMessage.ROLE_USER || units.isEmpty()) {
                units += mutableListOf(message)
            } else {
                units.last() += message
            }
        }
        return units
    }
}
