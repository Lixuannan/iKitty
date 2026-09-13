package com.example.aicat

/**
 * 猫咪的情绪状态。
 *
 * 与 [CatAnimation] 完全解耦：同一种情绪可以搭配不同动作，
 * 例如 HAPPY 可以配 BOUNCE、BLINK 或 TAIL_WAG。
 */
enum class CatMood {
    IDLE,
    LISTENING,
    THINKING,
    HAPPY,
    SAD,
    EXCITED,
    SLEEPY
}

/**
 * 猫咪的一次性动作。
 *
 * [NONE] 表示没有外部指定的动作，此时由 CatView 自行决定待机行为。
 * [durationMillis] 是动作的预期时长，供外部（例如 ViewModel）决定何时把它清回 [NONE]。
 */
enum class CatAnimation(val durationMillis: Long) {
    NONE(0L),
    BLINK(260L),
    LOOK_AROUND(2400L),
    TAIL_WAG(1600L),
    BOUNCE(700L),
    SHAKE(800L),
    YAWN(1900L);

    companion object {
        /** 模型没有明确给 animation 时，按情绪挑一个自然的默认动作。 */
        fun defaultFor(mood: CatMood): CatAnimation = when (mood) {
            CatMood.HAPPY -> TAIL_WAG
            CatMood.EXCITED -> BOUNCE
            CatMood.SLEEPY -> YAWN
            CatMood.IDLE, CatMood.LISTENING, CatMood.THINKING, CatMood.SAD -> NONE
        }
    }
}
