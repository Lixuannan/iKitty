package com.codingcow.ikitty

import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 上下文装配：长对话下"带哪几条"的契约。 */
class ContextAssemblerTest {

    /** 固定时区，断言才不依赖跑测试的机器。 */
    private val shanghai = TimeZone.of("Asia/Shanghai")

    private fun user(seq: Long, text: String) =
        StoredMessage(seq, StoredMessage.ROLE_USER, text, seq)

    private fun cat(seq: Long, text: String) =
        StoredMessage(seq, StoredMessage.ROLE_ASSISTANT, text, seq)

    private fun assemble(
        system: String,
        history: List<StoredMessage>,
        budget: Int,
        memory: String = "",
        ambient: String = ""
    ) = ContextAssembler.assemble(system, memory, ambient, history, budget, timeZone = shanghai)

    @Test
    fun `assembly starts with the system message and never with an assistant turn`() {
        val history = listOf(cat(1, "喵～你好呀"), user(2, "你好"), cat(3, "在呢"))
        val plan = assemble("你是猫", history, budget = 10_000)

        assertEquals("system", plan.messages.first().role)
        assertEquals("user", plan.messages[1].role)
        // 开场白被丢掉，不影响后面的问答
        assertEquals(2, plan.keptMessages)
        assertEquals(1, plan.droppedMessages)
    }

    @Test
    fun `over budget the oldest turns are dropped whole`() {
        val history = buildList {
            repeat(20) { index ->
                add(user(index * 2L + 1, "第 $index 轮：" + "字".repeat(40)))
                add(cat(index * 2L + 2, "回复 " + "字".repeat(40)))
            }
        }
        val plan = assemble("系统提示", history, budget = 300)

        assertTrue(plan.droppedMessages > 0)
        assertTrue(plan.keptMessages < history.size)
        assertEquals("user", plan.messages[1].role)
        // 最近一轮一定在，并且带着它自己的时间前缀
        assertEquals(history.last().contentForPrompt(shanghai), plan.messages.last().content)
    }

    @Test
    fun `the last turn survives even when it alone blows the budget`() {
        val history = listOf(user(1, "字".repeat(5000)), cat(2, "字".repeat(5000)))
        val plan = assemble("系统", history, budget = 100)
        assertEquals(2, plan.keptMessages)
    }

    @Test
    fun `local error messages never reach the wire`() {
        val history = listOf(
            user(1, "你好"),
            cat(2, "连接失败").copy(localError = true),
            user(3, "在吗"),
            cat(4, "在呢")
        )
        val plan = assemble("系统", history, budget = 10_000)
        assertEquals(3, plan.keptMessages)
        assertTrue(plan.messages.none { it.content == "连接失败" })
    }

    @Test
    fun `extra blocks join the system prompt only when present`() {
        val history = listOf(user(1, "你好"))
        assertEquals("系统", assemble("系统", history, 1000).messages.first().content)
        assertTrue(
            assemble("系统", history, 1000, memory = "【你记得的事】")
                .messages.first().content.contains("【你记得的事】")
        )
        // 「此刻」不进第一条 system，而是作为尾随 system 消息出现（见下面的专门用例）。
        assertTrue(
            assemble("系统", history, 1000, ambient = "【此刻】")
                .messages.last().content.contains("【此刻】")
        )
    }

    @Test
    fun `the ambient block stays out of the cached prefix`() {
        val history = listOf(user(1, "你好"), cat(2, "在呢"), user(3, "现在还早吗"))
        val plan = assemble("人设", history, 1000, memory = "记忆块", ambient = "此刻块")

        // system 里只有人设和记忆：它每轮都一样，服务商的缓存前缀才命中得了。
        val system = plan.messages.first().content
        assertTrue(system.indexOf("人设") < system.indexOf("记忆块"))
        assertFalse(system.contains("此刻块"), system)
    }

    /** 时刻必须紧贴生成点，否则长对话里模型会沿用几轮前自己说过的旧时间。 */
    @Test
    fun `the ambient block is the last message after the whole history`() {
        val history = listOf(
            user(1, "早上好"),
            cat(2, "早呀"),
            user(3, "现在几点了")
        )
        val plan = assemble("人设", history, 10_000, ambient = "【此刻】\n- 现在：2026-01-01 21:30 星期四")

        val last = plan.messages.last()
        assertEquals("system", last.role)
        assertTrue(last.content.contains("21:30"), last.content)
        // 历史仍然保持在它前面，且最后一条历史是用户这一轮（正文带上了它自己的时间前缀）。
        assertEquals(history.last().contentForPrompt(shanghai), plan.messages[plan.messages.size - 2].content)
        assertEquals("user", plan.messages[plan.messages.size - 2].role)
    }

    /** 时刻消息永远不能因为预算被挤掉——它正是解决"忘记时间"的那条信息。 */
    @Test
    fun `the ambient block survives even when the history alone blows the budget`() {
        val history = listOf(user(1, "字".repeat(5000)), cat(2, "字".repeat(5000)))
        val plan = assemble("系统", history, budget = 100, ambient = "【此刻】现在：21:30")

        assertTrue(plan.messages.last().content.contains("21:30"))
    }

    /** 没有「此刻」时不该凭空多出一条空 system 消息。 */
    @Test
    fun `no ambient block means no trailing system message`() {
        val plan = assemble("系统", listOf(user(1, "你好")), 1000)
        assertEquals(2, plan.messages.size)
        assertEquals(StoredMessage(1, StoredMessage.ROLE_USER, "你好", 1).contentForPrompt(shanghai), plan.messages.last().content)
    }

    @Test
    fun `budget leaves room for the reply and never goes negative`() {
        val spec = ModelCatalog.resolve("deepseek", "deepseek-v4-pro")
        val budget = ContextAssembler.budgetFor(spec, spec.maxTokens!!.max.toInt())
        assertTrue(budget >= ContextAssembler.MIN_INPUT_BUDGET)
        assertTrue(budget < spec.contextWindow)
    }

    @Test
    fun `chinese text costs more tokens than the same length of ascii`() {
        assertTrue(
            TokenEstimator.estimateText("喵喵喵喵喵喵喵喵") >
                TokenEstimator.estimateText("miaomiaomiao")
        )
    }

    @Test
    fun `the model name can carry the context window`() {
        assertEquals(8192, ModelCatalog.resolve("moonshot", "moonshot-v1-8k").contextWindow)
        assertEquals(131072, ModelCatalog.resolve("moonshot", "moonshot-v1-128k").contextWindow)
        assertEquals(1_000_000, ModelCatalog.resolve("zhipu", "glm-5.3").contextWindow)
        assertEquals(400_000, ModelCatalog.resolve("openai", "gpt-5.5").contextWindow)
        assertEquals(128_000, ModelCatalog.resolve("deepseek", "deepseek-v4-pro").contextWindow)
    }

    @Test
    fun `the ambient block eats into the history budget`() {
        val history = buildList {
            repeat(20) { index ->
                add(user(index * 2L + 1, "第 $index 轮：" + "字".repeat(40)))
                add(cat(index * 2L + 2, "回复 " + "字".repeat(40)))
            }
        }
        val lean = assemble("系统", history, budget = 300)
        val fat = assemble("系统", history, budget = 300, ambient = "此刻".repeat(60))

        // 背景块本身要花 token，所以装得下的历史轮数变少——这正是它计入预算的证据。
        assertTrue(fat.keptMessages < lean.keptMessages)
        assertTrue(fat.messages.last().content.contains("此刻"))
    }

    @Test
    fun `each image adds a fixed token cost`() {
        assertTrue(
            TokenEstimator.estimateMessage("看图", imageCount = 1) >=
                TokenEstimator.estimateMessage("看图") + TokenEstimator.IMAGE_TOKENS
        )
    }

    @Test
    fun `images are resolved into the wire format`() {
        val history = listOf(
            user(1, "第一轮"),
            cat(2, "回复"),
            StoredMessage(3, StoredMessage.ROLE_USER, "看图", 3, images = listOf("a.jpg", "gone.jpg"))
        )
        val plan = ContextAssembler.assemble(
            systemPrompt = "系统",
            memoryBlock = "",
            ambientBlock = "",
            history = history,
            budget = 10_000,
            imageUrl = { name -> if (name == "a.jpg") "data:image/jpeg;base64,AA" else null },
            timeZone = shanghai
        )
        // 文件已删除的图片被跳过，而不是让整条消息发送失败。
        assertEquals(listOf("data:image/jpeg;base64,AA"), plan.messages.last().images)
        assertEquals(history.last().contentForPrompt(shanghai), plan.messages.last().content)
    }

    /**
     * 「每条信息都应该有时间戳」的装配侧契约。
     *
     * 模型必须能从请求里读出每句话是什么时候说的；只给一个「现在」不足以让它算清时间线。
     */
    @Test
    fun `every message that reaches the wire carries its own timestamp`() {
        val history = listOf(
            StoredMessage(1, StoredMessage.ROLE_USER, "早上好", 1_700_000_000_000L),
            StoredMessage(2, StoredMessage.ROLE_ASSISTANT, "早呀", 1_700_000_030_000L),
            StoredMessage(3, StoredMessage.ROLE_USER, "现在几点了", 1_700_000_060_000L)
        )
        val plan = assemble("人设", history, budget = 10_000)

        // 除了第一条 system 与可能存在的「此刻」，其余全部是历史消息，条条带前缀。
        val kept = plan.messages.drop(1).filter { it.role != "system" }
        assertEquals(history.size, kept.size)
        assertEquals(
            listOf(
                "[2023-11-15 06:13 星期三] 早上好",
                "[2023-11-15 06:13 星期三] 早呀",
                "[2023-11-15 06:14 星期三] 现在几点了"
            ),
            kept.map { it.content }
        )
    }

    /**
     * 时间前缀必须是绝对时间：同一条历史在两轮请求里产生同样的字节，
     * 服务商的提示词缓存前缀才不会被它破坏。
     */
    @Test
    fun `history bytes do not change between two assemblies`() {
        val history = listOf(
            StoredMessage(1, StoredMessage.ROLE_USER, "早上好", 1_700_000_000_000L),
            StoredMessage(2, StoredMessage.ROLE_ASSISTANT, "早呀", 1_700_000_030_000L)
        )
        val first = assemble("人设", history, budget = 10_000, ambient = "【此刻】现在：09:00")
        val second = assemble("人设", history, budget = 10_000, ambient = "【此刻】现在：21:00")

        // 只有尾随的「此刻」不同，历史逐字节一致。
        assertEquals(
            first.messages.dropLast(1).map { it.content },
            second.messages.dropLast(1).map { it.content }
        )
    }

    @Test
    fun `images eat into the history budget`() {
        val base = buildList {
            repeat(10) { index ->
                add(user(index * 2L + 1, "第 $index 轮：" + "字".repeat(40)))
                add(cat(index * 2L + 2, "回复 " + "字".repeat(40)))
            }
        }
        val heavy = base + StoredMessage(
            seq = 100,
            role = StoredMessage.ROLE_USER,
            content = "看图",
            createdAt = 100,
            images = List(20) { "i$it.jpg" }
        )
        val lean = assemble("系统", base, budget = 3000)
        val fat = assemble("系统", heavy, budget = 3000)

        assertTrue(fat.keptMessages < lean.keptMessages)
    }
}
