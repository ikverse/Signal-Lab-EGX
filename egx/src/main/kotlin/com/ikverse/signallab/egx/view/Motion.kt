package com.ikverse.signallab.egx.view

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.delay

/**
 * The motion of the design, in one place. Everything here is an arrival or a reply to a finger: it plays once when a page
 * opens or a value changes, never on a loop, except the "now" marker's breath and halo. With Android's animations off
 * ([LocalReduceMotion]) everything shows at rest at once and nothing breathes.
 */
object Motion {
    /** Between one card's arrival and the next. */
    const val STAGGER_MS = 60L
    const val DRAW_MS = 700
    val arrive = spring<Float>(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow)
    val press = spring<Float>(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium)
    /** The same spring, for a length: the sliding highlight and the paging dots. */
    val slide = spring<androidx.compose.ui.unit.Dp>(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium)
}

/** 0 to 1 once, [order] staggers apart, keyed on [key] so a new value arrives again. 1 at once with motion off. */
@Composable
fun rememberArrival(order: Int = 0, key: Any? = Unit): State<Float> {
    val still = LocalReduceMotion.current
    val a = remember(key) { Animatable(if (still) 1f else 0f) }
    LaunchedEffect(a) {
        if (a.value < 1f) {
            delay(order * Motion.STAGGER_MS)
            a.animateTo(1f, Motion.arrive)
        }
    }
    return a.asState()
}

/** 0 to 1 over [ms] once, eased, after [delayMs]: for lines that draw themselves in and bars that grow. */
@Composable
fun rememberDraw(key: Any?, delayMs: Long = 0, ms: Int = Motion.DRAW_MS): State<Float> {
    val still = LocalReduceMotion.current
    val a = remember(key) { Animatable(if (still) 1f else 0f) }
    LaunchedEffect(a) {
        if (a.value < 1f) {
            delay(delayMs)
            a.animateTo(1f, tween(ms, easing = FastOutSlowInEasing))
        }
    }
    return a.asState()
}

/** A card's arrival: it rises a little, grows from 92% and fades in, [order] after the first. */
fun Modifier.arrive(order: Int = 0, key: Any? = Unit): Modifier = composed {
    val p by rememberArrival(order, key)
    graphicsLayer {
        val s = 0.92f + 0.08f * p
        scaleX = s
        scaleY = s
        alpha = p.coerceIn(0f, 1f)
        translationY = (1f - p) * 18f * density
    }
}

/** A row sliding in from the start side, for the ladder's levels. */
fun Modifier.slideIn(order: Int, key: Any? = Unit): Modifier = composed {
    val p by rememberArrival(order, key)
    graphicsLayer {
        alpha = p.coerceIn(0f, 1f)
        translationX = (1f - p) * -14f * density
    }
}

/** Shrinks a little under the finger and springs back: every button and card that opens something. */
fun Modifier.pressable(source: MutableInteractionSource): Modifier = composed {
    val pressed by source.collectIsPressedAsState()
    val still = LocalReduceMotion.current
    val s by animateFloatAsState(if (pressed && !still) 0.96f else 1f, Motion.press, label = "press")
    graphicsLayer { scaleX = s; scaleY = s }
}

/** A value that counts toward [target] from where it was, so a figure that changes is seen to change. */
@Composable
fun rememberCount(target: Double): Double {
    val still = LocalReduceMotion.current
    val a = remember { Animatable(if (still) target.toFloat() else 0f) }
    LaunchedEffect(target) { if (still) a.snapTo(target.toFloat()) else a.animateTo(target.toFloat(), tween(900, easing = FastOutSlowInEasing)) }
    return a.value.toDouble()
}

/** 1 to 1.3 and back, forever, for the latest close's dot. Still at 1 with motion off. */
@Composable
fun rememberBreath(): Float {
    if (LocalReduceMotion.current) return 1f
    val t = rememberInfiniteTransition(label = "breath")
    val v by t.animateFloat(1f, 1.3f, infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breath")
    return v
}

/** 0 to 1 every 1.4 s, for the "now" marker's halo. Still at 0 with motion off. */
@Composable
fun rememberHalo(): Float {
    if (LocalReduceMotion.current) return 0f
    val t = rememberInfiniteTransition(label = "halo")
    val v by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1400), RepeatMode.Restart), label = "halo")
    return v
}

/** The tick's stamp when an order is marked placed: it lands from large and turned, overshoots and settles. */
@Composable
fun rememberStamp(on: Boolean): Float {
    val still = LocalReduceMotion.current
    val a = remember { Animatable(if (on) 1f else 0f) }
    val first = remember { mutableFloatStateOf(1f) }
    LaunchedEffect(on) {
        if (!on) a.snapTo(0f)
        else if (still || first.floatValue == 0f && a.value == 1f) a.snapTo(1f)
        else a.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMedium))
        first.floatValue = 0f
    }
    return a.value
}
