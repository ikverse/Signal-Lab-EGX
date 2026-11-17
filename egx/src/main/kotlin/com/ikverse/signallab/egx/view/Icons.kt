package com.ikverse.signallab.egx.view

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * Each strategy's small mark, beside its name wherever it appears: a coin for the dividend run, a diamond for calm stocks,
 * a square for the payers, a leaf for the slow breakout, a lens for the low-risk formula and a plain square for the control.
 */
@Composable
fun StrategyGlyph(id: String, modifier: Modifier = Modifier, size: Dp = 12.dp) {
    val color = LocalBoard.current.strategyColor(id)
    val shape = strategyIndex(id)
    Canvas(modifier.size(size)) {
        val w = this.size.width
        when (shape) {
            0 -> drawCircle(color)
            1 -> rotate(45f) { drawRoundRect(color, Offset(w * 0.15f, w * 0.15f), Size(w * 0.7f, w * 0.7f), CornerRadius(w * 0.15f)) }
            2 -> drawRoundRect(color, cornerRadius = CornerRadius(w * 0.28f))
            3 -> drawPath(Path().apply {
                moveTo(w / 2, 0f); cubicTo(w, 0f, w, w, w, w); lineTo(w / 2, w); cubicTo(0f, w, 0f, 0f, w / 2, 0f); close()
            }, color)
            4 -> drawPath(Path().apply {
                moveTo(0f, w / 2); quadraticTo(w / 2, -w * 0.15f, w, w / 2); quadraticTo(w / 2, w * 1.15f, 0f, w / 2); close()
            }, color)
            else -> drawRoundRect(color, cornerRadius = CornerRadius(w * 0.12f))
        }
    }
}

/** The back arrow, pointing to the start side in either direction of reading. */
@Composable
fun BackGlyph(color: Color, modifier: Modifier = Modifier) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Canvas(modifier.size(16.dp)) {
        val w = size.width
        val s = Stroke(width = w * 0.16f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val p = if (rtl) Path().apply { moveTo(w * 0.38f, w * 0.2f); lineTo(w * 0.68f, w * 0.5f); lineTo(w * 0.38f, w * 0.8f) }
        else Path().apply { moveTo(w * 0.62f, w * 0.2f); lineTo(w * 0.32f, w * 0.5f); lineTo(w * 0.62f, w * 0.8f) }
        drawPath(p, color, style = s)
    }
}

/** A tick, for "Placed the order" and the Levels switch. */
@Composable
fun TickGlyph(color: Color, modifier: Modifier = Modifier, size: Dp = 16.dp) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        drawPath(Path().apply { moveTo(w * 0.2f, w * 0.52f); lineTo(w * 0.42f, w * 0.72f); lineTo(w * 0.8f, w * 0.3f) }, color,
            style = Stroke(width = w * 0.14f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}
