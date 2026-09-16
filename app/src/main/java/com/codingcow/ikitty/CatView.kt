package com.codingcow.ikitty

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

private const val IDLE_ANIMATION_MIN_MILLIS = 3_000L
private const val IDLE_ANIMATION_MAX_MILLIS = 8_000L

private val IDLE_ANIMATIONS = listOf(
    CatAnimation.BLINK,
    CatAnimation.LOOK_AROUND,
    CatAnimation.TAIL_WAG,
    CatAnimation.YAWN
)

/**
 * 用 Compose Canvas 画的猫咪。[mood] 决定表情，[animation] 是一次性动作。
 *
 * 这是一个自包含组件：造型、表情、动画、待机行为都在内部完成，
 * 外部只需要告诉它当前状态。想换成 Rive / Lottie 时整体替换本文件即可。
 */
@Composable
fun CatView(
    mood: CatMood,
    animation: CatAnimation = CatAnimation.NONE,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "cat")

    // 呼吸：身体轻微起伏
    val breath by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breath"
    )

    // 悬浮：整体上下轻轻飘
    val float by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "float"
    )

    val expression = remember(mood) { expressionFor(mood) }
    val state = remember { CatAnimationState() }
    val lifecycleOwner = LocalLifecycleOwner.current

    // 外部给的动作优先；只有 IDLE 才跑随机待机行为。
    // repeatOnLifecycle 保证 App 退到后台时停止调度，不在后台空转。
    LaunchedEffect(lifecycleOwner, mood, animation) {
        state.reset()
        if (animation != CatAnimation.NONE) {
            state.play(animation)
            return@LaunchedEffect
        }
        if (mood != CatMood.IDLE) return@LaunchedEffect

        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (isActive) {
                delay(Random.nextLong(IDLE_ANIMATION_MIN_MILLIS, IDLE_ANIMATION_MAX_MILLIS + 1))
                state.play(IDLE_ANIMATIONS.random())
            }
        }
    }

    Canvas(modifier = modifier) {
        // 动画值全部在 draw lambda 内读取，只触发重绘，不触发重组。
        val blink = state.blink.value
        val look = state.look.value
        val tailWag = state.tailWag.value
        val bounce = state.bounce.value
        val shake = state.shake.value
        val yawn = state.yawn.value

        val s = min(size.width, size.height)
        val originX = (size.width - s) / 2f
        val originY = (size.height - s) / 2f
        val floatOffset = (float - 0.5f) * s * 0.045f
        val bounceOffset = -bounce * s * 0.085f
        val breathScale = 1f + (breath - 0.5f) * 0.030f

        withTransform({
            translate(originX, originY + floatOffset + bounceOffset)
            scale(breathScale, breathScale, pivot = Offset(s * 0.5f, s * 0.92f))
        }) {
            drawOval(
                CatPalette.Shadow,
                topLeft = Offset(0.310f * s, 0.930f * s),
                size = Size(0.380f * s, 0.035f * s)
            )

            // 尾巴在身体后面，只随 TAIL_WAG 摆动
            drawTail(s, tailWag)

            drawBody(s)
            drawHead(
                s = s,
                expression = expression,
                blink = blink,
                look = look,
                shake = shake,
                yawn = yawn,
                pulse = breath
            )
        }

        drawMotionMarks(s, bounce, shake)
    }
}

/** 消息列表里用的小猫头像，复用同一套头部画法。 */
@Composable
fun CatAvatar(modifier: Modifier = Modifier) {
    val idle = remember { expressionFor(CatMood.IDLE) }
    Canvas(modifier = modifier.fillMaxSize()) {
        // 让「耳尖到下巴」正好落进头像框
        val s = min(size.width, size.height) / 0.70f
        withTransform({
            translate(size.width / 2f - 0.5f * s, size.height / 2f - 0.3185f * s)
        }) {
            drawHead(
                s = s,
                expression = idle,
                blink = 1f,
                look = 0f,
                shake = 0f,
                yawn = 0f,
                pulse = 0.5f
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 表情模型
// ---------------------------------------------------------------------------

private enum class EyeStyle { OPEN, ARC }

private data class CatExpression(
    /** 基础睁眼程度，1 = 正常。会被眨眼和哈欠继续调制。 */
    val eyeOpen: Float = 1f,
    /** 瞳孔偏移，单位是眼睛半径。 */
    val pupilDx: Float = 0f,
    val pupilDy: Float = 0f,
    /** 眼睑盖住眼睛的比例，0 = 全开。 */
    val lid: Float = 0f,
    /** 眼睑倾斜，做出下垂感。 */
    val lidTilt: Float = 0f,
    /** 弯月眼抬起的幅度。 */
    val happyLift: Float = 1f,
    /** 耳朵姿态：-1 向外压平，0 自然，1 竖直警觉。 */
    val earPerk: Float = 0f,
    val eyeStyle: EyeStyle = EyeStyle.OPEN,
    /** 嘴的宽度与下垂量（负数 = 下弯）。 */
    val mouthSpan: Float = 0.030f,
    val mouthDrop: Float = 0.020f,
    /** 张嘴幅度，0 = 闭着嘴。 */
    val openMouth: Float = 0f,
    val sadBrows: Boolean = false,
    val sparkles: Boolean = false,
    val thinking: Boolean = false
)

private fun expressionFor(mood: CatMood): CatExpression = when (mood) {
    CatMood.IDLE -> CatExpression()

    CatMood.LISTENING -> CatExpression(
        eyeOpen = 1.08f,
        earPerk = 0.85f,
        mouthSpan = 0.026f,
        mouthDrop = 0.016f
    )

    CatMood.THINKING -> CatExpression(
        pupilDx = 0.28f,
        pupilDy = -0.32f,
        earPerk = 0.25f,
        mouthSpan = 0.024f,
        mouthDrop = 0.014f,
        thinking = true
    )

    CatMood.HAPPY -> CatExpression(
        earPerk = 0.45f,
        eyeStyle = EyeStyle.ARC,
        mouthSpan = 0.040f,
        mouthDrop = 0.032f
    )

    CatMood.SAD -> CatExpression(
        eyeOpen = 0.95f,
        pupilDy = 0.28f,
        lid = 0.13f,
        lidTilt = 0.17f,
        earPerk = -0.62f,
        mouthSpan = 0.030f,
        mouthDrop = -0.014f,
        sadBrows = true
    )

    CatMood.EXCITED -> CatExpression(
        eyeOpen = 1.12f,
        earPerk = 1f,
        openMouth = 0.85f,
        sparkles = true
    )

    CatMood.SLEEPY -> CatExpression(
        lid = 0.46f,
        earPerk = -0.30f,
        mouthSpan = 0.022f,
        mouthDrop = 0.008f
    )
}

// ---------------------------------------------------------------------------
// 动画状态
// ---------------------------------------------------------------------------

/** CatView 内部的一次性动画数值，全部收敛在 0..1 或 -1..1。 */
private class CatAnimationState {
    /** 1 = 完全睁开，0 = 完全闭上。 */
    val blink = Animatable(1f)

    /** -1 = 向左看，1 = 向右看。 */
    val look = Animatable(0f)

    /** -1..1 尾巴摆动幅度。 */
    val tailWag = Animatable(0f)

    /** 0..1 跳跃高度。 */
    val bounce = Animatable(0f)

    /** -1..1 头部左右晃动。 */
    val shake = Animatable(0f)

    /** 0..1 张嘴程度。 */
    val yawn = Animatable(0f)

    suspend fun reset() {
        blink.snapTo(1f)
        look.snapTo(0f)
        tailWag.snapTo(0f)
        bounce.snapTo(0f)
        shake.snapTo(0f)
        yawn.snapTo(0f)
    }

    suspend fun play(animation: CatAnimation) {
        when (animation) {
            CatAnimation.NONE -> Unit

            CatAnimation.BLINK -> {
                blink.animateTo(0.02f, tween(durationMillis = 90, easing = LinearEasing))
                blink.animateTo(1f, tween(durationMillis = 150, easing = FastOutSlowInEasing))
            }

            CatAnimation.LOOK_AROUND -> {
                look.animateTo(1f, tween(durationMillis = 550, easing = FastOutSlowInEasing))
                delay(350)
                look.animateTo(-1f, tween(durationMillis = 750, easing = FastOutSlowInEasing))
                delay(300)
                look.animateTo(0f, tween(durationMillis = 380))
            }

            CatAnimation.TAIL_WAG -> {
                repeat(3) {
                    tailWag.animateTo(1f, tween(durationMillis = 210, easing = FastOutSlowInEasing))
                    tailWag.animateTo(-1f, tween(durationMillis = 210, easing = FastOutSlowInEasing))
                }
                tailWag.animateTo(0f, tween(durationMillis = 240))
            }

            CatAnimation.BOUNCE -> {
                bounce.animateTo(1f, tween(durationMillis = 170, easing = FastOutSlowInEasing))
                bounce.animateTo(0f, tween(durationMillis = 330, easing = FastOutSlowInEasing))
            }

            CatAnimation.SHAKE -> {
                repeat(2) {
                    shake.animateTo(1f, tween(durationMillis = 90))
                    shake.animateTo(-1f, tween(durationMillis = 90))
                }
                shake.animateTo(0f, tween(durationMillis = 120))
            }

            CatAnimation.YAWN -> {
                blink.animateTo(0.05f, tween(durationMillis = 240))
                yawn.animateTo(1f, tween(durationMillis = 430, easing = FastOutSlowInEasing))
                delay(520)
                yawn.animateTo(0f, tween(durationMillis = 400))
                blink.animateTo(1f, tween(durationMillis = 240))
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 配色：低饱和的暖灰棕 + 奶油白 + 柔和粉
// ---------------------------------------------------------------------------

private object CatPalette {
    /** 头部、耳朵、尾巴主色。 */
    val Main = Color(0xFFCFB49F)

    /** 身体比头略浅，形成柔和的层次。 */
    val Body = Color(0xFFDFCCBA)

    /** 胸口、腹部、口鼻、爪子。 */
    val Cream = Color(0xFFFDF8F2)

    val Shade = Color(0xFFB99E8B)
    val EarInner = Color(0xFFEEA9B4)
    val Nose = Color(0xFFE08E9A)
    val Eye = Color(0xFF4A3B33)
    /** 竖瞳，比虹膜更深一点。 */
    val Pupil = Color(0xFF2E2521)
    val EyeWarm = Color(0xFF84695A)
    val Shine = Color(0xFFFFFFFF)
    val Mouth = Color(0xFF9A6A5C)
    val MouthFill = Color(0xFFD98A97)
    val Tongue = Color(0xFFF0A3AE)
    val Blush = Color(0xFFF0A9B0)
    val Whisker = Color(0xFFBCA794)
    val Shadow = Color(0x388A7466)
    val Motion = Color(0xFFCDB6AB)
    val Sparkle = Color(0xFFF2C98C)
}

// ---------------------------------------------------------------------------
// 基础绘制工具
// ---------------------------------------------------------------------------

private fun lerp(start: Float, stop: Float, fraction: Float): Float =
    start + (stop - start) * fraction

private fun cubicPoints(p0: Offset, p1: Offset, p2: Offset, p3: Offset, steps: Int): List<Offset> =
    (0..steps).map { i ->
        val t = i.toFloat() / steps
        val mt = 1f - t
        Offset(
            mt * mt * mt * p0.x + 3f * mt * mt * t * p1.x + 3f * mt * t * t * p2.x + t * t * t * p3.x,
            mt * mt * mt * p0.y + 3f * mt * mt * t * p1.y + 3f * mt * t * t * p2.y + t * t * t * p3.y
        )
    }

private fun rotatePoint(point: Offset, pivot: Offset, degrees: Float): Offset {
    val r = Math.toRadians(degrees.toDouble())
    val cs = cos(r).toFloat()
    val sn = sin(r).toFloat()
    val dx = point.x - pivot.x
    val dy = point.y - pivot.y
    return Offset(pivot.x + dx * cs - dy * sn, pivot.y + dx * sn + dy * cs)
}

/** 用一圈同心椭圆叠出柔和的渐隐边缘，替代生硬的实心色块。 */
private fun DrawScope.softOval(
    center: Offset,
    rx: Float,
    ry: Float,
    color: Color,
    peak: Float,
    layers: Int
) {
    for (i in layers downTo 1) {
        val f = i.toFloat() / layers
        val alpha = peak * (1f - f).pow(1.6f)
        if (alpha <= 0.012f) continue
        val rxi = rx * f
        val ryi = ry * f
        drawOval(
            color.copy(alpha = alpha),
            topLeft = Offset(center.x - rxi, center.y - ryi),
            size = Size(rxi * 2f, ryi * 2f)
        )
    }
}

/** 沿曲线撒一串半径渐变的圆，得到有粗细变化、毛绒感的形状。 */
private fun DrawScope.taperedRibbon(
    spine: List<Offset>,
    startWidth: Float,
    endWidth: Float,
    color: Color,
    steps: Int
) {
    val last = spine.size - 1
    if (last <= 0) return
    for (i in 0..steps) {
        val t = i.toFloat() / steps
        val pos = t * last
        val idx = pos.toInt().coerceAtMost(last - 1)
        val local = pos - idx
        val p = spine[idx]
        val q = spine[idx + 1]
        val center = Offset(lerp(p.x, q.x, local), lerp(p.y, q.y, local))
        drawCircle(color, radius = lerp(startWidth, endWidth, t) / 2f, center = center)
    }
}

/** 三个顶点构成的圆角三角形，用于耳朵。 */
private fun roundedTrianglePath(a: Offset, b: Offset, c: Offset, t: Float): Path {
    val verts = listOf(a, b, c)
    val path = Path()
    for (i in 0..2) {
        val v = verts[i]
        val prev = verts[(i + 2) % 3]
        val next = verts[(i + 1) % 3]
        val start = Offset(lerp(v.x, prev.x, t), lerp(v.y, prev.y, t))
        val end = Offset(lerp(v.x, next.x, t), lerp(v.y, next.y, t))
        if (i == 0) path.moveTo(start.x, start.y) else path.lineTo(start.x, start.y)
        path.quadraticTo(v.x, v.y, end.x, end.y)
    }
    path.close()
    return path
}

// ---------------------------------------------------------------------------
// 身体：坐姿幼猫，胸口宽、肚子圆，前面两只短腿和猫爪
// ---------------------------------------------------------------------------

private fun DrawScope.drawBody(s: Float) {
    val body = Path().apply {
        moveTo(0.360f * s, 0.498f * s)
        cubicTo(0.296f * s, 0.540f * s, 0.276f * s, 0.648f * s, 0.278f * s, 0.748f * s)
        cubicTo(0.280f * s, 0.858f * s, 0.332f * s, 0.930f * s, 0.500f * s, 0.932f * s)
        cubicTo(0.668f * s, 0.930f * s, 0.720f * s, 0.858f * s, 0.722f * s, 0.748f * s)
        cubicTo(0.724f * s, 0.648f * s, 0.704f * s, 0.540f * s, 0.640f * s, 0.498f * s)
        cubicTo(0.576f * s, 0.468f * s, 0.424f * s, 0.468f * s, 0.360f * s, 0.498f * s)
        close()
    }
    drawPath(body, CatPalette.Body)

    // 胸腹奶油白
    softOval(Offset(0.5f * s, 0.730f * s), 0.150f * s, 0.170f * s, CatPalette.Cream, peak = 0.95f, layers = 9)
    // 后腿外侧一点体积感
    softOval(Offset(0.302f * s, 0.790f * s), 0.090f * s, 0.105f * s, CatPalette.Shade, peak = 0.16f, layers = 5)
    softOval(Offset(0.698f * s, 0.790f * s), 0.090f * s, 0.105f * s, CatPalette.Shade, peak = 0.16f, layers = 5)

    // 两只前腿
    for (cx in listOf(0.418f, 0.582f)) {
        drawRoundRect(
            CatPalette.Body,
            topLeft = Offset((cx - 0.058f) * s, 0.742f * s),
            size = Size(0.116f * s, 0.160f * s),
            cornerRadius = CornerRadius(0.058f * s, 0.058f * s)
        )
    }
    // 两腿之间的分缝
    drawLine(
        CatPalette.Shade.copy(alpha = 0.45f),
        Offset(0.5f * s, 0.752f * s),
        Offset(0.5f * s, 0.890f * s),
        strokeWidth = 0.007f * s,
        cap = StrokeCap.Round
    )

    // 圆润的猫爪 + 极简脚趾分割
    for (cx in listOf(0.418f, 0.582f)) {
        softOval(Offset(cx * s, 0.884f * s), 0.074f * s, 0.050f * s, CatPalette.Cream, peak = 1f, layers = 5)
        for (offset in listOf(-0.021f, 0.021f)) {
            drawLine(
                CatPalette.Shade.copy(alpha = 0.55f),
                Offset((cx + offset) * s, 0.866f * s),
                Offset((cx + offset) * s, 0.898f * s),
                strokeWidth = 0.006f * s,
                cap = StrokeCap.Round
            )
        }
    }
}

/** 蓬松的猫尾：根部粗、尾端圆，绕到身体一侧。 */
private fun DrawScope.drawTail(s: Float, wag: Float) {
    val spine = cubicPoints(
        Offset(0.660f * s, 0.852f * s),
        Offset(0.818f * s, 0.898f * s),
        Offset((0.906f + wag * 0.02f) * s, 0.792f * s),
        Offset((0.892f + wag * 0.04f) * s, 0.674f * s),
        steps = 32
    ) + cubicPoints(
        Offset((0.892f + wag * 0.04f) * s, 0.674f * s),
        Offset((0.880f + wag * 0.06f) * s, 0.592f * s),
        Offset((0.828f + wag * 0.07f) * s, 0.548f * s),
        Offset((0.774f + wag * 0.06f) * s, 0.578f * s),
        steps = 24
    )

    taperedRibbon(spine, 0.105f * s, 0.076f * s, CatPalette.Main, steps = 52)
    taperedRibbon(
        spine.subList((spine.size * 0.62f).toInt(), spine.size),
        0.088f * s,
        0.074f * s,
        CatPalette.Cream,
        steps = 26
    )
}

// ---------------------------------------------------------------------------
// 头部：比正圆更宽，脸颊外鼓，下巴圆润
// ---------------------------------------------------------------------------

private fun headPath(s: Float): Path = Path().apply {
    moveTo(0.500f * s, 0.148f * s)
    cubicTo(0.352f * s, 0.148f * s, 0.252f * s, 0.196f * s, 0.228f * s, 0.312f * s)
    cubicTo(0.204f * s, 0.418f * s, 0.238f * s, 0.552f * s, 0.320f * s, 0.592f * s)
    cubicTo(0.402f * s, 0.632f * s, 0.598f * s, 0.632f * s, 0.680f * s, 0.592f * s)
    cubicTo(0.762f * s, 0.552f * s, 0.796f * s, 0.418f * s, 0.772f * s, 0.312f * s)
    cubicTo(0.748f * s, 0.196f * s, 0.648f * s, 0.148f * s, 0.500f * s, 0.148f * s)
    close()
}

private fun DrawScope.drawHead(
    s: Float,
    expression: CatExpression,
    blink: Float,
    look: Float,
    shake: Float,
    yawn: Float,
    pulse: Float
) {
    val shift = shake * 0.018f * s
    // 头部可以独立于身体晃动 / 轻微歪头
    withTransform({
        translate(shift, 0f)
        rotate(look * 2.2f, pivot = Offset(0.5f * s, 0.520f * s))
    }) {
        drawPath(headPath(s), CatPalette.Main)

        drawEars(s, expression.earPerk)

        // 口鼻 + 下巴的浅色区
        softOval(Offset(0.5f * s, 0.518f * s), 0.150f * s, 0.092f * s, CatPalette.Cream, peak = 0.72f, layers = 8)

        // 腮红：柔和渐隐，不是硬边圆点
        softOval(Offset(0.298f * s, 0.495f * s), 0.070f * s, 0.046f * s, CatPalette.Blush, peak = 0.95f, layers = 8)
        softOval(Offset(0.702f * s, 0.495f * s), 0.070f * s, 0.046f * s, CatPalette.Blush, peak = 0.95f, layers = 8)

        val eyeOpen = (expression.eyeOpen * blink * (1f - yawn * 0.92f)).coerceIn(0.02f, 1.3f)
        drawEyes(
            s = s,
            style = expression.eyeStyle,
            eyeOpen = eyeOpen,
            lookDx = expression.pupilDx + look * 0.6f,
            lookDy = expression.pupilDy,
            lid = expression.lid,
            lidTilt = expression.lidTilt,
            happyLift = expression.happyLift
        )

        if (expression.sadBrows) drawSadBrows(s)

        drawNoseAndMouth(s, expression, yawn)
        drawWhiskers(s)

        if (expression.sparkles) drawSparkles(s, pulse)
        if (expression.thinking) drawThinkingDots(s, pulse)
    }
}

/**
 * 猫耳：上端尖、耳根宽、向外张开。
 * 只摆动耳尖、耳根固定在头上，否则整只旋转会把耳根也带歪。
 */
private fun DrawScope.drawEars(s: Float, perk: Float) {
    for (left in listOf(true, false)) {
        val baseOuter: Offset
        val tipBase: Offset
        val baseInner: Offset
        if (left) {
            baseOuter = Offset(0.222f * s, 0.300f * s)
            tipBase = Offset(0.238f * s, 0.018f * s)
            baseInner = Offset(0.452f * s, 0.170f * s)
        } else {
            baseOuter = Offset(0.778f * s, 0.300f * s)
            tipBase = Offset(0.762f * s, 0.018f * s)
            baseInner = Offset(0.548f * s, 0.170f * s)
        }

        val pivot = Offset((baseOuter.x + baseInner.x) / 2f, (baseOuter.y + baseInner.y) / 2f)
        // 正 perk = 耳尖向内立起（警觉）；负 perk = 耳尖向外压平（下垂）
        val factor = if (perk >= 0f) 22f else 40f
        val angle = perk * factor
        val tip = rotatePoint(tipBase, pivot, if (left) angle else -angle)

        drawPath(roundedTrianglePath(baseOuter, tip, baseInner, 0.11f), CatPalette.Main)

        // 内耳：向外耳重心收缩并稍微下移
        val cx = (baseOuter.x + tip.x + baseInner.x) / 3f
        val cy = (baseOuter.y + tip.y + baseInner.y) / 3f
        fun shrink(p: Offset) = Offset(
            cx + (p.x - cx) * 0.50f,
            cy + (p.y - cy) * 0.50f + 0.024f * s
        )
        drawPath(
            roundedTrianglePath(shrink(baseOuter), shrink(tip), shrink(baseInner), 0.11f),
            CatPalette.EarInner
        )
    }
}

/** 猫眼的圆杏仁轮廓。 */
private fun almondPath(cx: Float, cy: Float, rx: Float, ry: Float): Path = Path().apply {
    moveTo(cx - rx, cy)
    quadraticTo(cx - rx * 0.55f, cy - ry, cx, cy - ry * 0.96f)
    quadraticTo(cx + rx * 0.55f, cy - ry, cx + rx, cy)
    quadraticTo(cx + rx * 0.55f, cy + ry, cx, cy + ry * 0.96f)
    quadraticTo(cx - rx * 0.55f, cy + ry, cx - rx, cy)
    close()
}

private fun DrawScope.drawEyes(
    s: Float,
    style: EyeStyle,
    eyeOpen: Float,
    lookDx: Float,
    lookDy: Float,
    lid: Float,
    lidTilt: Float,
    happyLift: Float
) {
    for (cx in listOf(0.392f, 0.608f)) {
        val center = Offset(cx * s, 0.390f * s)
        val rx = 0.064f * s
        val ry = 0.072f * s

        if (style == EyeStyle.ARC && eyeOpen > 0.4f) {
            drawHappyEye(center, rx, ry, happyLift)
            continue
        }

        if (eyeOpen < 0.16f) {
            val arc = Path().apply {
                moveTo(center.x - rx * 0.9f, center.y + ry * 0.05f)
                quadraticTo(center.x, center.y - ry * 0.28f, center.x + rx * 0.9f, center.y + ry * 0.05f)
            }
            drawPath(arc, CatPalette.Eye, style = Stroke(width = 0.021f * s, cap = StrokeCap.Round))
            continue
        }

        val rx2 = rx * min(1.12f, if (eyeOpen > 1f) eyeOpen else 1f)
        val ry2 = ry * min(1.12f, eyeOpen)
        val ox = center.x + lookDx * rx
        val oy = center.y + lookDy * ry

        drawPath(almondPath(ox, oy, rx2, ry2), CatPalette.Eye)

        // 竖瞳：猫眼最直接的识别特征
        drawOval(
            CatPalette.Pupil.copy(alpha = 0.47f),
            topLeft = Offset(ox - rx2 * 0.22f, oy - ry2 * 0.70f),
            size = Size(rx2 * 0.44f, ry2 * 1.40f)
        )
        // 虹膜下缘的暖光，避免眼睛变成死黑圆球
        softOval(
            Offset(ox, oy + ry2 * 0.42f),
            rx2 * 0.62f,
            ry2 * 0.34f,
            CatPalette.EyeWarm,
            peak = 0.45f,
            layers = 5
        )

        if (eyeOpen > 0.5f) {
            drawCircle(
                CatPalette.Shine,
                radius = 0.013f * s,
                center = Offset(ox - rx2 * 0.60f + 0.013f * s, oy - ry2 * 0.62f + 0.013f * s)
            )
            drawCircle(
                CatPalette.Shine.copy(alpha = 0.8f),
                radius = 0.0065f * s,
                center = Offset(ox + rx2 * 0.18f + 0.0065f * s, oy + ry2 * 0.20f + 0.0065f * s)
            )
        }

        // 眼睑：盖住眼睛上半部分，做出半闭和下垂
        if (lid > 0.01f) {
            val lidY = oy - ry2 + ry2 * 2f * lid
            val tilt = lidTilt * ry2
            val x0 = ox - rx2 - 0.010f * s
            val x1 = ox + rx2 + 0.010f * s
            val y0 = oy - ry2 - 0.010f * s
            val cover = Path().apply {
                moveTo(x0, y0)
                lineTo(x1, y0)
                lineTo(x1, lidY + tilt)
                quadraticTo(ox, lidY - tilt - ry2 * 0.10f, x0, lidY - tilt)
                close()
            }
            drawPath(cover, CatPalette.Main)
        }
    }
}

private fun DrawScope.drawHappyEye(center: Offset, rx: Float, ry: Float, lift: Float) {
    val arc = Path().apply {
        moveTo(center.x - rx, center.y + ry * 0.18f)
        quadraticTo(center.x, center.y + ry * 0.18f - ry * lift, center.x + rx, center.y + ry * 0.18f)
    }
    drawPath(arc, CatPalette.Eye, style = Stroke(width = rx * 0.42f, cap = StrokeCap.Round))
}

private fun DrawScope.drawSadBrows(s: Float) {
    val color = CatPalette.Eye.copy(alpha = 0.63f)
    val width = 0.011f * s
    for (sign in listOf(-1, 1)) {
        drawLine(
            color,
            Offset((0.5f - sign * 0.070f) * s, 0.312f * s),
            Offset((0.5f - sign * 0.155f) * s, 0.344f * s),
            strokeWidth = width,
            cap = StrokeCap.Round
        )
    }
}

private fun DrawScope.drawNoseAndMouth(s: Float, expression: CatExpression, yawn: Float) {
    val noseX = 0.5f * s
    val noseY = 0.478f * s

    // 鼻子：小巧的倒三角，上缘微弧
    val nose = Path().apply {
        moveTo(noseX - 0.026f * s, noseY)
        quadraticTo(noseX, noseY - 0.008f * s, noseX + 0.026f * s, noseY)
        quadraticTo(noseX + 0.016f * s, noseY + 0.024f * s, noseX, noseY + 0.030f * s)
        quadraticTo(noseX - 0.016f * s, noseY + 0.024f * s, noseX - 0.026f * s, noseY)
        close()
    }
    drawPath(nose, CatPalette.Nose)

    val openAmount = maxOf(yawn, expression.openMouth)
    val mouthTopY = if (openAmount > 0.05f) 0.520f * s else 0.532f * s

    // 鼻子到嘴的短线
    drawLine(
        CatPalette.Mouth.copy(alpha = 0.75f),
        Offset(noseX, noseY + 0.030f * s),
        Offset(noseX, mouthTopY),
        strokeWidth = 0.007f * s,
        cap = StrokeCap.Round
    )

    if (openAmount > 0.05f) {
        val halfWidth = 0.050f * s * (0.4f + openAmount * 0.8f) / 2f
        val height = 0.056f * s * openAmount
        val mouth = Path().apply {
            moveTo(noseX - halfWidth, mouthTopY)
            quadraticTo(noseX - halfWidth, mouthTopY + height, noseX, mouthTopY + height)
            quadraticTo(noseX + halfWidth, mouthTopY + height, noseX + halfWidth, mouthTopY)
            close()
        }
        drawPath(mouth, CatPalette.MouthFill)
        drawOval(
            CatPalette.Tongue,
            topLeft = Offset(noseX - halfWidth * 0.66f, mouthTopY + height * 0.55f),
            size = Size(halfWidth * 1.32f, height * 0.85f)
        )
        return
    }

    // 很轻的两瓣猫嘴
    for (sign in listOf(-1, 1)) {
        val arc = Path().apply {
            moveTo(noseX, mouthTopY)
            quadraticTo(
                noseX + sign * expression.mouthSpan * 0.55f * s,
                mouthTopY + expression.mouthDrop * 1.6f * s,
                noseX + sign * expression.mouthSpan * s,
                mouthTopY + expression.mouthDrop * 0.25f * s
            )
        }
        drawPath(arc, CatPalette.Mouth, style = Stroke(width = 0.012f * s, cap = StrokeCap.Round))
    }
}

/** 短胡须：从脸颊长出，只略微超出头部轮廓。 */
private fun DrawScope.drawWhiskers(s: Float) {
    val color = CatPalette.Whisker
    val width = 0.0062f * s

    listOf(0.492f to 0.480f, 0.516f to 0.520f, 0.540f to 0.556f)
        .forEachIndexed { index, (startY, stopY) ->
            val startX = 0.330f * s
            val stopX = (0.196f - index * 0.005f) * s
            val midX = (startX + stopX) / 2f
            val midY = ((startY + stopY) / 2f - 0.006f) * s
            for (mirror in listOf(false, true)) {
                val a = if (mirror) s - startX else startX
                val m = if (mirror) s - midX else midX
                val b = if (mirror) s - stopX else stopX
                val path = Path().apply {
                    moveTo(a, startY * s)
                    quadraticTo(m, midY, b, stopY * s)
                }
                drawPath(path, color, style = Stroke(width = width, cap = StrokeCap.Round))
            }
        }
}

private fun DrawScope.drawSparkles(s: Float, pulse: Float) {
    val alpha = 0.6f + 0.4f * pulse
    listOf(
        Triple(0.168f, 0.228f, 0.040f),
        Triple(0.832f, 0.192f, 0.033f),
        Triple(0.878f, 0.312f, 0.024f)
    ).forEach { (dx, dy, radius) ->
        drawSparkle(Offset(dx * s, dy * s), radius * s, CatPalette.Sparkle.copy(alpha = alpha))
    }
}

private fun DrawScope.drawSparkle(center: Offset, size: Float, color: Color) {
    val path = Path().apply {
        moveTo(center.x, center.y - size)
        quadraticTo(center.x + size * 0.16f, center.y - size * 0.16f, center.x + size, center.y)
        quadraticTo(center.x + size * 0.16f, center.y + size * 0.16f, center.x, center.y + size)
        quadraticTo(center.x - size * 0.16f, center.y + size * 0.16f, center.x - size, center.y)
        quadraticTo(center.x - size * 0.16f, center.y - size * 0.16f, center.x, center.y - size)
        close()
    }
    drawPath(path, color)
}

private fun DrawScope.drawThinkingDots(s: Float, pulse: Float) {
    listOf(
        Triple(0.822f, 0.226f, 0.013f),
        Triple(0.874f, 0.180f, 0.017f),
        Triple(0.922f, 0.126f, 0.021f)
    ).forEachIndexed { index, (dx, dy, radius) ->
        val lift = (pulse - 0.5f) * 0.016f * (index + 1)
        softOval(
            Offset(dx * s, (dy - lift) * s),
            radius * s,
            radius * s,
            CatPalette.Main,
            peak = 0.28f + 0.30f * pulse,
            layers = 4
        )
    }
}

/** BOUNCE / SHAKE 的动作线。 */
private fun DrawScope.drawMotionMarks(s: Float, bounce: Float, shake: Float) {
    if (bounce > 0.04f) {
        for (sign in listOf(-1, 1)) {
            val x = (0.5f + sign * 0.290f) * s
            val path = Path().apply {
                moveTo(x, (0.700f - bounce * 0.03f) * s)
                quadraticTo((0.5f + sign * 0.245f) * s, 0.772f * s, x, (0.845f - bounce * 0.03f) * s)
            }
            drawPath(
                path,
                CatPalette.Motion.copy(alpha = 0.78f * bounce),
                style = Stroke(width = 0.010f * s, cap = StrokeCap.Round)
            )
        }
    }

    val shakeAmount = abs(shake)
    if (shakeAmount > 0.05f) {
        for (sign in listOf(-1, 1)) {
            val x = (0.5f + sign * 0.300f) * s
            val path = Path().apply {
                moveTo(x, 0.290f * s)
                quadraticTo((0.5f + sign * 0.260f) * s, 0.372f * s, x, 0.454f * s)
            }
            drawPath(
                path,
                CatPalette.Motion.copy(alpha = 0.75f * shakeAmount),
                style = Stroke(width = 0.010f * s, cap = StrokeCap.Round)
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 预览
// ---------------------------------------------------------------------------

@Preview(showBackground = true, widthDp = 760)
@Composable
private fun CatViewPreviewMoods() {
    MaterialTheme {
        Row {
            listOf(CatMood.IDLE, CatMood.LISTENING, CatMood.THINKING, CatMood.HAPPY, CatMood.SAD).forEach {
                Box(Modifier.size(150.dp).padding(4.dp)) { CatView(mood = it, modifier = Modifier.fillMaxSize()) }
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 760)
@Composable
private fun CatViewPreviewMore() {
    MaterialTheme {
        Row {
            listOf(CatMood.EXCITED, CatMood.SLEEPY).forEach {
                Box(Modifier.size(150.dp).padding(4.dp)) { CatView(mood = it, modifier = Modifier.fillMaxSize()) }
            }
            Box(Modifier.size(150.dp).padding(4.dp)) {
                CatView(mood = CatMood.HAPPY, animation = CatAnimation.BOUNCE, modifier = Modifier.fillMaxSize())
            }
            Box(Modifier.size(150.dp).padding(4.dp)) {
                CatView(mood = CatMood.IDLE, animation = CatAnimation.YAWN, modifier = Modifier.fillMaxSize())
            }
        }
    }
}
