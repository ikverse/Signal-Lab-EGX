package com.ikverse.signallab.egx.widget

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalSize
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.ikverse.signallab.egx.EgxApplication
import com.ikverse.signallab.egx.MainActivity
import com.ikverse.signallab.egx.data.EgxTime
import com.ikverse.signallab.egx.i18n.Tr
import com.ikverse.signallab.egx.ledger.Records
import com.ikverse.signallab.egx.view.TodayView
import com.ikverse.signallab.egx.view.todayView
import kotlinx.coroutines.flow.first

/** What the widget says, worked out from Today with no Android in it. */
data class WidgetView(val dateLine: String, val sentence: String, val counts: String, val account: String, val against: String)

fun widgetView(today: TodayView, tr: Tr): WidgetView {
    val buys = today.cards.count { it.buy }
    val sells = today.cards.size - buys
    return WidgetView(
        today.dateLine, today.sentence,
        if (today.cards.isEmpty()) "" else tr.t("Buy %1\$d · Sell %2\$d", buys, sells),
        today.account,
        if (today.you == null) tr.t(Tr.NOT_YET) else "${today.barsText.first} · ${today.barsText.second}",
    )
}

/**
 * Tomorrow on the home screen (item 41 of 0.2.0): the plan's sentence, the count of buys and sells, and the paper
 * account against the EGX30. Small: the sentence only. Refreshed after each evening pass; a tap opens Today.
 */
class TodayWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(SMALL, WIDE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val graph = (context.applicationContext as EgxApplication).graph
        val dao = graph.ledger.dao()
        val tr = graph.tr()
        val prefs = graph.settings.value
        val today = todayView(
            dao.latestCalls().first(), dao.allDays(Records.LEVELS).first(), dao.yardsticks().first(), dao.fills(Records.LEVELS).first(),
            prefs, emptyList(), tr, { code -> (graph.stocks[code]?.let { if (tr.isArabic) it.nameAr ?: it.nameEn else it.nameEn } ?: code) to code },
            EgxTime.date(EgxTime.today(System.currentTimeMillis())),
        )
        val v = widgetView(today, tr)
        provideContent { Content(v) }
    }

    @androidx.compose.runtime.Composable
    private fun Content(v: WidgetView) {
        val wide = LocalSize.current.width >= WIDE.width
        Column(
            GlanceModifier.fillMaxSize().cornerRadius(24.dp).background(PAGE).padding(14.dp).clickable(actionStartActivity<MainActivity>()),
        ) {
            Text(v.dateLine, style = TextStyle(color = MUTED, fontSize = 12.sp), maxLines = 1)
            Spacer(GlanceModifier.height(4.dp))
            Text(v.sentence, style = TextStyle(color = INK, fontSize = if (wide) 18.sp else 16.sp, fontWeight = FontWeight.Bold), maxLines = if (wide) 2 else 3)
            if (wide) {
                Spacer(GlanceModifier.height(6.dp))
                if (v.counts.isNotEmpty()) Text(v.counts, style = TextStyle(color = BUY, fontSize = 13.sp, fontWeight = FontWeight.Medium), maxLines = 1)
                Row {
                    Text(v.account, style = TextStyle(color = INK, fontSize = 13.sp, fontWeight = FontWeight.Medium), maxLines = 1)
                    Spacer(GlanceModifier.width(8.dp))
                    Text(v.against, style = TextStyle(color = MUTED, fontSize = 13.sp), maxLines = 1)
                }
            }
        }
    }

    companion object {
        val SMALL = DpSize(110.dp, 110.dp)
        val WIDE = DpSize(250.dp, 110.dp)

        // The design's colours ("Calm with cards"): light page and text, then dark.
        private val PAGE = ColorProvider(day = Color(0xFFF3F6F4), night = Color(0xFF181C1B))
        private val INK = ColorProvider(day = Color(0xFF0F1513), night = Color(0xFFEEF2F0))
        private val MUTED = ColorProvider(day = Color(0xFF64706B), night = Color(0xFF8C9692))
        private val BUY = ColorProvider(day = Color(0xFF0B8F63), night = Color(0xFF3DD9A0))

        /** Redraws every placed widget; called after each evening pass. Harmless when none is placed. */
        suspend fun refresh(context: Context) {
            runCatching { TodayWidget().updateAll(context) }
        }
    }
}

class TodayWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TodayWidget()
}
