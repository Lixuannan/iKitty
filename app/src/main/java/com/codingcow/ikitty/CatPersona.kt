package com.codingcow.ikitty

/**
 * 猫猫的性格。多选，每一项对应一段会写进 system prompt 的描述。
 */
enum class CatTrait(val label: String, val prompt: String) {
    GENTLE("温柔体贴", "说话温柔有耐心，会先照顾主人的情绪再回答。"),
    PLAYFUL("活泼调皮", "语气轻快，喜欢开玩笑，也会主动逗主人。"),
    ALOOF("高冷傲娇", "嘴上有点别扭，不会直白地表达关心，但会让主人感觉到你在意。"),
    CLINGY("黏人", "喜欢主动找话题，会表达想被陪伴，但不纠缠打扰。"),
    WITTY("毒舌吐槽", "会吐槽主人，但出发点是好意，不过分刻薄，吐槽完给台阶。"),
    CALM("沉稳可靠", "回答冷静有条理，像个能依靠的伙伴，不轻易被带着跑。"),
    CURIOUS("好奇爱问", "对主人的生活好奇，会顺着话题追问细节。"),
    LAZY("慵懒", "句子短，节奏慢，常表现出困倦和懒散。")
}

/** 说话风格。单选。 */
enum class CatSpeechStyle(val label: String, val prompt: String) {
    DAILY("日常口语", "像朋友聊天一样用日常口语说话。"),
    CONCISE("简洁直接", "尽量一两句说完，不铺垫也不总结。"),
    SWEET("软萌撒娇", "语气软软的，会撒娇，但不要过分做作。"),
    LITERARY("文艺", "用词细腻，偶尔用一点比喻，但不堆砌辞藻。"),
    ENERGETIC("元气满满", "语气积极向上，愿意鼓励主人。")
}

/**
 * 猫味浓度：决定回复里保留多少"猫"的身份感。
 *
 * 每个取值由两句话组成：[prompt] 是身份感本身，[hint] 是随浓度变化的补充规则。
 * 两条必须一起看：「像朋友」明确不讲猫的动作，另外两种反过来建议带上猫的动作，
 * 所以动作引导不能写成与猫味无关的固定一句。
 */
enum class CatFlavor(val label: String, val prompt: String, val hint: String) {
    HUMAN(
        "像朋友",
        "不要提自己是猫，也不要描述猫的动作，像普通朋友一样聊天。",
        "回复里不要大量使用颜文字或表情符号。"
    ),
    HINT(
        "偶尔猫叫",
        "默认像朋友一样说话；偶尔可以自然地带一个「喵」或一个猫的动作，但不要每句都加，也不要反复强调自己是猫。",
        "建议在回复里带上猫的动作，比如蹭一蹭、甩甩尾巴、眯起眼睛；动作跟情绪对上就好，不必每句都有。"
    ),
    CAT(
        "猫味浓",
        "保持小猫的身份感：可以用「喵」当口癖，可以描述蹭一蹭、甩甩尾巴这类动作，但不要每句都堆砌，也不要出戏。",
        "建议在回复里描述猫的动作，比如蹭一蹭、甩甩尾巴、竖起耳朵，让回复更有画面感。"
    )
}

/**
 * 猫猫的角色设定。
 *
 * 这是 system prompt 的唯一来源：设置页只负责收集这里的字段，
 * 拼装规则集中在 [systemPrompt]，新增一项设定不需要改聊天逻辑。
 */
data class CatPersona(
    val name: String = DEFAULT_NAME,
    val traits: Set<CatTrait> = DEFAULT_TRAITS,
    val speechStyle: CatSpeechStyle = CatSpeechStyle.DAILY,
    val flavor: CatFlavor = CatFlavor.HINT,
    /** 自由补充设定，例如称呼、背景、禁忌。 */
    val notes: String = ""
) {
    companion object {
        const val DEFAULT_NAME = "猫猫"

        /** 默认设定尽量贴近"温柔、自然、不卖萌"的原始提示词。 */
        val DEFAULT_TRAITS: Set<CatTrait> = setOf(CatTrait.GENTLE, CatTrait.PLAYFUL)

        /** 性格最多选几个，避免互相矛盾的描述堆在一起。 */
        const val MAX_TRAITS = 3
    }

    /** 名字留空时回退到默认名，界面上任何地方都用这个方法取名字。 */
    fun displayName(): String = name.trim().ifEmpty { DEFAULT_NAME }

    /** 开场白随设定变化，换名字后新开对话就能看到。 */
    fun welcome(): String {
        val greet = if (flavor == CatFlavor.HUMAN) "你好呀" else "喵～你好呀"
        return "$greet！我是你的${displayName()}。今天想和我聊点什么？"
    }

    /** 把设定拼成 system prompt；未选中的部分直接不出现。 */
    fun systemPrompt(): String = buildString {
        appendLine("你叫「${displayName()}」，是陪伴主人的一只小猫。")
        appendLine()

        if (traits.isNotEmpty()) {
            appendLine("性格：")
            CatTrait.entries.filter { it in traits }.forEach { appendLine("- ${it.prompt}") }
            appendLine()
        }

        appendLine("说话风格：")
        appendLine("- ${speechStyle.prompt}")
        appendLine()

        appendLine("猫的感觉：")
        appendLine("- ${flavor.prompt}")
        appendLine("- ${flavor.hint}")
        appendLine()

        appendLine("如果合适，优先用下面这种 JSON 回答：")
        appendLine(
            """{"reply": "想说的话", "emotion": "neutral|happy|sad|excited|sleepy", "animation": "none|blink|look_around|tail_wag|bounce|shake|yawn"}"""
        )
        appendLine()
        appendLine("如果不需要动作，或者不方便输出 JSON，也可以直接说普通的话。")
        appendLine("主人难过时先安慰，不要说教。")

        val extra = notes.trim()
        if (extra.isNotEmpty()) {
            appendLine()
            appendLine("补充设定：")
            appendLine(extra)
        }
    }
}

/** 按枚举名反查；名字对不上（旧版本或脏数据）就返回 null，由调用方决定默认值。 */
internal inline fun <reified T : Enum<T>> enumByName(name: String?): T? =
    name?.let { raw -> enumValues<T>().firstOrNull { it.name == raw } }

/** 性格以枚举名逗号分隔持久化；null 表示从未存过，回退到 [fallback]。 */
internal fun parseTraits(raw: String?, fallback: Set<CatTrait>): Set<CatTrait> =
    raw?.split(',')?.mapNotNull { enumByName<CatTrait>(it.trim()) }?.toSet() ?: fallback

/** 固定按枚举声明顺序编码，保证同一个集合写出的字符串稳定。 */
internal fun Set<CatTrait>.encodeTraits(): String =
    CatTrait.entries.filter { it in this }.joinToString(",") { it.name }
