package com.ikverse.signallab.egx.view

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * "Calm with cards" (accepted 2026-11-14, https://claude.ai/artifact/6qDLTnGJke5fcWiXXXjPhX): every colour of the mockup,
 * light and dark. Colour is used only for buy, sell and the wash at the top.
 */
@Immutable
data class Calm(
    val page: Color,
    val ink: Color,
    val muted: Color,
    val line: Color,
    val wash1: Color,
    val wash2: Color,
    val surface: Color,
    val buyCard: Color,
    val sellCard: Color,
    val dock: Color,
    val dockEdge: Color,
    val action: Color,
    val actionInk: Color,
    val up: Color,
    val down: Color,
) {
    companion object {
        val LIGHT = Calm(
            page = Color(0xFFF3F6F4), ink = Color(0xFF0F1513), muted = Color(0xFF64706B), line = Color(0xFFDCE2DF),
            wash1 = Color(0xFFCDF3E2), wash2 = Color(0xFFD5E5FF), surface = Color(0xFFFFFFFF), buyCard = Color(0xFFE0F4EA),
            sellCard = Color(0xFFFBE5E2), dock = Color(0xC7FFFFFF), dockEdge = Color(0xFFDCE2DF), action = Color(0xFF0F1513),
            actionInk = Color.White, up = Color(0xFF0B8F63), down = Color(0xFFD6453D),
        )
        val DARK = Calm(
            page = Color(0xFF0B0E0D), ink = Color(0xFFEEF2F0), muted = Color(0xFF8C9692), line = Color(0xFF262C2A),
            wash1 = Color(0xFF0F3327), wash2 = Color(0xFF14264A), surface = Color(0xFF181C1B), buyCard = Color(0xFF13281F),
            sellCard = Color(0xFF2D1816), dock = Color(0xD1282E2C), dockEdge = Color(0xFF333A37), action = Color(0xFFEEF2F0),
            actionInk = Color(0xFF0B0E0D), up = Color(0xFF3DD9A0), down = Color(0xFFFF7A72),
        )
    }
}

val LocalCalm = staticCompositionLocalOf { Calm.LIGHT }

/**
 * The type of the design: Readex Pro for Arabic and English. Until its font file is in the app (it has to be downloaded
 * from Google Fonts, which waits on the owner's OK), the phone's own sans-serif stands in; swapping [Family] is all it takes.
 */
object Type {
    val Family: FontFamily = FontFamily.SansSerif
    val sentence = TextStyle(fontFamily = Family, fontSize = 32.sp, lineHeight = 42.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp)
    val action = TextStyle(fontFamily = Family, fontSize = 40.sp, lineHeight = 46.sp, fontWeight = FontWeight.Bold)
    val actionSmall = TextStyle(fontFamily = Family, fontSize = 24.sp, fontWeight = FontWeight.Bold)
    val name = TextStyle(fontFamily = Family, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
    val nameSmall = TextStyle(fontFamily = Family, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    val tile = TextStyle(fontFamily = Family, fontSize = 25.sp, fontWeight = FontWeight.Bold)
    val body = TextStyle(fontFamily = Family, fontSize = 15.sp, lineHeight = 22.sp)
    val chip = TextStyle(fontFamily = Family, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    val small = TextStyle(fontFamily = Family, fontSize = 12.5.sp)
    val menu = TextStyle(fontFamily = Family, fontSize = 13.sp)
}

@Composable
fun CalmTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val c = if (dark) Calm.DARK else Calm.LIGHT
    val scheme = if (dark) darkColorScheme(background = c.page, surface = c.surface, onBackground = c.ink, onSurface = c.ink, primary = c.action)
    else lightColorScheme(background = c.page, surface = c.surface, onBackground = c.ink, onSurface = c.ink, primary = c.action)
    CompositionLocalProvider(LocalCalm provides c) {
        MaterialTheme(colorScheme = scheme, typography = Typography(bodyLarge = Type.body, bodyMedium = Type.body), content = content)
    }
}

/** The page: the neutral background with the soft mint and sky wash behind the top of the screen. */
@Composable
fun CalmPage(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val c = LocalCalm.current
    Box(
        modifier
            .fillMaxSize()
            .background(c.page)
            .drawBehind {
                val h = minOf(size.height, 320f * density)
                drawRect(Brush.radialGradient(listOf(c.wash1, Color.Transparent), center = Offset(size.width * 0.8f, 0f), radius = size.width * 0.6f), size = size.copy(height = h))
                drawRect(Brush.radialGradient(listOf(c.wash2, Color.Transparent), center = Offset(size.width * 0.15f, 0f), radius = size.width * 0.6f), size = size.copy(height = h))
            },
        content = content,
    )
}
