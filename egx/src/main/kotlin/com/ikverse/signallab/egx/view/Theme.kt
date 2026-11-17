package com.ikverse.signallab.egx.view

import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.ikverse.signallab.egx.R
import com.ikverse.signallab.engine.egx.EgxStrategies

/**
 * "Bright board" (accepted 2026-11-17, https://claude.ai/artifact/K9XwUp8835BSXzWxPKGtZ4): the light theme is "Paper and
 * stone", one warm-neutral family with nothing pure white; the dark one a near-black navy. Both carry the indigo-to-teal
 * gradient on the one thing to press, the current page's name, the chart line and the "now" marker.
 */
@Immutable
data class Board(
    val dark: Boolean,
    val page: Color,
    /** The two soft glows behind the top of every screen. */
    val glow1: Color,
    val glow2: Color,
    val cardTop: Color,
    val cardBottom: Color,
    val ink: Color,
    val muted: Color,
    val line: Color,
    val chip: Color,
    val buy: Color,
    val buyTop: Color,
    val buyBottom: Color,
    val sell: Color,
    val sellTop: Color,
    val sellBottom: Color,
    /** Timing chips and the "when" card. */
    val gold: Color,
    val onGold: Color,
    val accent1: Color,
    val accent2: Color,
    val onAccent: Color,
    /** The EGX30's line, the entry band, the "now" marker's halo. */
    val violet: Color,
    /** The price ladder: green above the entry, rose below. */
    val rise1: Color,
    val rise2: Color,
    val fall1: Color,
    val fall2: Color,
    /** One colour per strategy, in [EgxStrategies.all] order. */
    val strategies: List<Color>,
) {
    val card: Color get() = cardTop
    val cardBrush: Brush get() = Brush.verticalGradient(listOf(cardTop, cardBottom))
    val buyBrush: Brush get() = Brush.linearGradient(listOf(buyTop, buyBottom))
    val sellBrush: Brush get() = Brush.linearGradient(listOf(sellTop, sellBottom))
    val accent: Brush get() = Brush.linearGradient(listOf(accent1, accent2))

    companion object {
        val LIGHT = Board(
            dark = false,
            page = Color(0xFFECE8E1), glow1 = Color(0x293B34B8), glow2 = Color(0x240C6A80),
            cardTop = Color(0xFFE4E0D8), cardBottom = Color(0xFFDAD5CC),
            ink = Color(0xFF1D1C1A), muted = Color(0xFF57534C), line = Color(0x26282218), chip = Color(0x0F282218),
            buy = Color(0xFF0B5C43), buyTop = Color(0xFFCADED1), buyBottom = Color(0xFFB6D3C7),
            sell = Color(0xFF9C1535), sellTop = Color(0xFFE7CAC9), sellBottom = Color(0xFFDEB9BD),
            gold = Color(0xFFD9BC78), onGold = Color(0xFF2B2108),
            accent1 = Color(0xFF3B34B8), accent2 = Color(0xFF0C6A80), onAccent = Color.White,
            violet = Color(0xFF3B34B8),
            rise1 = Color(0xFF0E9F6E), rise2 = Color(0xFF8FDDBF), fall1 = Color(0xFFE0496A), fall2 = Color(0xFFF2A9B8),
            strategies = listOf(Color(0xFF8A6512), Color(0xFF3B34B8), Color(0xFF0B6FB8), Color(0xFF0B5C43), Color(0xFFA21C6B), Color(0xFF6A665E)),
        )
        val DARK = Board(
            dark = true,
            page = Color(0xFF0A0E16), glow1 = Color(0x576366F1), glow2 = Color(0x4D0C7C91),
            cardTop = Color(0xFF172131), cardBottom = Color(0xFF101722),
            ink = Color(0xFFE9EEF6), muted = Color(0xFF93A0B5), line = Color(0x21FFFFFF), chip = Color(0x12FFFFFF),
            buy = Color(0xFF3DDC9F), buyTop = Color(0xFF0F4335), buyBottom = Color(0xFF0B2A33),
            sell = Color(0xFFFF7A90), sellTop = Color(0xFF4C1F2D), sellBottom = Color(0xFF2A1626),
            gold = Color(0xFFDDAA4A), onGold = Color(0xFF1F1705),
            accent1 = Color(0xFF5B54F0), accent2 = Color(0xFF0C7C91), onAccent = Color.White,
            violet = Color(0xFFA5B4FC),
            rise1 = Color(0xFF34D399), rise2 = Color(0xFF17624B), fall1 = Color(0xFFFB7185), fall2 = Color(0xFF7A2B3C),
            strategies = listOf(Color(0xFFDDAA4A), Color(0xFFA5B4FC), Color(0xFF67C7F5), Color(0xFF3DDC9F), Color(0xFFF29BC9), Color(0xFF93A0B5)),
        )
    }
}

val LocalBoard = staticCompositionLocalOf { Board.LIGHT }

/** True when Android's animations are off: every entrance then shows at rest, and nothing loops. */
val LocalReduceMotion = staticCompositionLocalOf { false }

/** A strategy's own colour, and its place for its glyph. Unknown ids take the last, quiet one. */
fun Board.strategyColor(id: String): Color = strategies[strategyIndex(id)]

fun strategyIndex(id: String): Int = EgxStrategies.all.indexOfFirst { it.id == id }.let { if (it < 0) EgxStrategies.all.size - 1 else it }
    .coerceIn(0, 5)

/** One weight of Readex Pro's variable font, for reading. */
@OptIn(ExperimentalTextApi::class)
private fun readex(w: Int) = Font(R.font.readex_pro, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))

/**
 * The type: IBM Plex Sans Arabic for headlines, large figures and the page names; Readex Pro for everything read. Both are
 * Google Fonts under the SIL Open Font Licence, shipped in `assets/licences`.
 */
object Type {
    val Body: FontFamily = FontFamily(readex(300), readex(400), readex(500), readex(600), readex(700))
    val Display: FontFamily = FontFamily(Font(R.font.plex_arabic_semibold, FontWeight.SemiBold), Font(R.font.plex_arabic_bold, FontWeight.Bold))

    // No letter spacing anywhere: Arabic letters join, and spacing them makes Android measure a word too narrow and
    // break its last letter onto a new line. Every style has a line height, so a tall Arabic letter never touches the next line.
    val tabLarge = TextStyle(fontFamily = Display, fontSize = 32.sp, lineHeight = 44.sp, fontWeight = FontWeight.Bold)
    val tabSmall = TextStyle(fontFamily = Body, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
    val sentence = TextStyle(fontFamily = Display, fontSize = 30.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold)
    val sentenceLarge = TextStyle(fontFamily = Display, fontSize = 40.sp, lineHeight = 52.sp, fontWeight = FontWeight.Bold)
    val title = TextStyle(fontFamily = Display, fontSize = 26.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold)
    val action = TextStyle(fontFamily = Display, fontSize = 38.sp, lineHeight = 46.sp, fontWeight = FontWeight.Bold)
    val actionSmall = TextStyle(fontFamily = Display, fontSize = 26.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold)
    val figure = TextStyle(fontFamily = Display, fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold)
    val figureSmall = TextStyle(fontFamily = Display, fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold)
    val name = TextStyle(fontFamily = Body, fontSize = 19.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold)
    val nameSmall = TextStyle(fontFamily = Body, fontSize = 16.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold)
    val tile = figure
    val lead = TextStyle(fontFamily = Body, fontSize = 15.sp, lineHeight = 23.sp)
    val body = TextStyle(fontFamily = Body, fontSize = 14.sp, lineHeight = 21.sp)
    val bodyStrong = TextStyle(fontFamily = Body, fontSize = 14.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold)
    val chip = TextStyle(fontFamily = Body, fontSize = 12.5.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)
    val small = TextStyle(fontFamily = Body, fontSize = 12.sp, lineHeight = 18.sp)
    val tag = TextStyle(fontFamily = Body, fontSize = 11.5.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium)
    val tagFigure = TextStyle(fontFamily = Body, fontSize = 11.5.sp, lineHeight = 16.sp, fontWeight = FontWeight.Bold)
}

@Composable
fun BoardTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val b = if (dark) Board.DARK else Board.LIGHT
    val context = LocalContext.current
    val still = remember(context) {
        runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)
    }
    // Material's own parts (switches, text fields, the sheet) take the design's colours, never the default purple.
    val scheme = if (dark) darkColorScheme(
        background = b.page, surface = b.cardTop, onBackground = b.ink, onSurface = b.ink, primary = b.accent1, onPrimary = b.onAccent,
        outline = b.muted, outlineVariant = b.line, onSurfaceVariant = b.muted, surfaceVariant = b.cardBottom, surfaceContainerHighest = b.cardBottom,
        surfaceContainerLow = b.cardTop, surfaceContainerHigh = b.cardTop, secondary = b.accent2, onSecondary = b.onAccent, error = b.sell,
    ) else lightColorScheme(
        background = b.page, surface = b.cardTop, onBackground = b.ink, onSurface = b.ink, primary = b.accent1, onPrimary = b.onAccent,
        outline = b.muted, outlineVariant = b.line, onSurfaceVariant = b.muted, surfaceVariant = b.cardBottom, surfaceContainerHighest = b.cardBottom,
        surfaceContainerLow = b.cardTop, surfaceContainerHigh = b.cardTop, secondary = b.accent2, onSecondary = b.onAccent, error = b.sell,
    )
    CompositionLocalProvider(LocalBoard provides b, LocalReduceMotion provides still) {
        MaterialTheme(colorScheme = scheme, typography = Typography(bodyLarge = Type.body, bodyMedium = Type.body), content = content)
    }
}

/**
 * The page: the plain ground with the indigo and teal glows behind the top of the screen. The colour fills the whole
 * window, behind the status bar and the gesture bar; only the content keeps clear of them. Each glow is a circle that fades
 * out fully inside the screen, so it never ends in a line.
 */
@Composable
fun BoardPage(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val b = LocalBoard.current
    Box(
        modifier
            .fillMaxSize()
            .background(b.page)
            .drawBehind {
                val r = minOf(size.width * 0.9f, 420f * density)
                val right = Offset(size.width * 0.88f, -0.08f * r)
                val left = Offset(size.width * 0.06f, -0.06f * r)
                drawCircle(Brush.radialGradient(listOf(b.glow1, Color.Transparent), center = right, radius = r), radius = r, center = right)
                drawCircle(Brush.radialGradient(listOf(b.glow2, Color.Transparent), center = left, radius = r * 0.9f), radius = r * 0.9f, center = left)
            },
    ) {
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing), content = content)
    }
}
