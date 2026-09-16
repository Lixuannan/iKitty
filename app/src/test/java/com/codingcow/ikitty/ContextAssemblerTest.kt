package com.codingcow.ikitty

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 上下文装配：长对话下"带哪几条"的契约。 */
class ContextAssemblerTest {

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
    ) = ContextAssembler.assemble(system, memory, ambient, history, budget)

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
        // 最近一轮一定在
        assertEquals(history.last().content, plan.messages.last().content)
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
        assertTrue(
            assemble("系统", history, 1000, ambient = "【此刻】")
                .messages.first().content.contains("【此刻】")
        )
    }

    @Test
    fun `stable blocks come before volatile ones so the cached prefix survives`() {
        val history = listOf(user(1, "你好"))
        val system = assemble("人设", history, 1000, memory = "记忆块", ambient = "此刻块")
            .messages.first().content

        assertTrue(system.indexOf("人设") < system.indexOf("记忆块"))
        assertTrue(system.indexOf("记忆块") < system.indexOf("此刻块"))
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
        assertTrue(fat.messages.first().content.contains("此刻"))
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
            imageUrl = { name -> if (name == "a.jpg") "data:image/jpeg;base64,AA" else null }
        )
        // 文件已删除的图片被跳过，而不是让整条消息发送失败。
        assertEquals(listOf("data:image/jpeg;base64,AA"), plan.messages.last().images)
        assertEquals("看图", plan.messages.last().content)
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
