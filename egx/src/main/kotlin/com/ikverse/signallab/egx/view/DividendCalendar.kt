package com.ikverse.signallab.egx.view

import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ikverse.signallab.egx.data.EgxDividend
import com.ikverse.signallab.egx.data.EgxTime
import com.ikverse.signallab.egx.i18n.Tr
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.format.TextStyle
import java.util.Locale

/** One date on the dividend calendar: a stock's last day to hold, or the day its cash arrives. */
data class DividendEntry(
    val code: String,
    val name: String,
    val day: Long,
    val cash: Boolean,
    val title: String,
    val line: String,
    /** A Dividend run call holds the stock on paper now. */
    val runOpen: Boolean,
    /** The text the phone's calendar gets for this date. */
    val calendarTitle: String,
)

/** A month of the dividend calendar, ready to draw. */
data class DividendMonth(
    val title: String,
    val month: YearMonth,
    /** The month's days in weeks of Sunday to Saturday (the exchange's week); null pads the first and last week. */
    val weeks: List<List<LocalDate?>>,
    val marked: Set<LocalDate>,
    val entries: List<DividendEntry>,
    val weekdays: List<String>,
)

/**
 * The dividend calendar from the dividends already collected (Arab Finance's news and the owner's own entries): each
 * dividend's last day to hold and its cash date that fall in [month], with its amount, its yield at the stock's last
 * close and whether a Dividend run call holds it.
 */
fun dividendMonth(
    dividends: List<EgxDividend>,
    lastCloses: Map<String, Double>,
    runOpen: Set<String>,
    month: YearMonth,
    tr: Tr,
    names: (String) -> String,
): DividendMonth {
    val locale = if (tr.isArabic) Locale.forLanguageTag("ar-EG") else Locale.ENGLISH
    val entries = dividends.filter { !it.cancelled }.flatMap { d ->
        val amount = d.amount?.let { tr.t("EGP %1\$s a share", tr.price(it)) } ?: tr.t("Amount not known yet")
        val yieldText = d.amount?.let { a -> lastCloses[d.code]?.takeIf { it > 0 }?.let { tr.t("yield %1\$s at the last close", tr.percent(a / it)) } }
        val line = listOfNotNull(amount, yieldText).joinToString(" · ")
        val name = names(d.code)
        listOfNotNull(
            d.lastCumDay?.let {
                DividendEntry(d.code, name, it, false, tr.t("Last day to hold"), line, d.code in runOpen,
                    tr.t("%1\$s: last day to hold for the dividend", "${d.code} $name"))
            },
            d.paymentDay?.let {
                DividendEntry(d.code, name, it, true, tr.t("Cash date"), line, d.code in runOpen,
                    tr.t("%1\$s: dividend cash date", "${d.code} $name"))
            },
        )
    }.filter { YearMonth.from(EgxTime.date(it.day)) == month }.sortedWith(compareBy({ it.day }, { it.cash }, { it.code }))
    val first = month.atDay(1)
    val pad = first.dayOfWeek.value % 7 // Sunday is 0
    val cells = List(pad) { null } + (1..month.lengthOfMonth()).map { month.atDay(it) }
    val weeks = (cells + List((7 - cells.size % 7) % 7) { null }).chunked(7)
    val weekdays = listOf(DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY)
        .map { it.getDisplayName(TextStyle.SHORT, locale) }
    return DividendMonth(
        tr.digits("${month.month.getDisplayName(TextStyle.FULL_STANDALONE, locale)} ${month.year}"), month, weeks,
        entries.map { EgxTime.date(it.day) }.toSet(), entries, weekdays,
    )
}

/** Opens the phone's calendar with the date filled in, as an all-day event; the app asks for no calendar permission. */
fun addToCalendar(context: Context, e: DividendEntry) {
    val start = EgxTime.date(e.day).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
    val intent = Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
        .putExtra(CalendarContract.Events.TITLE, e.calendarTitle)
        .putExtra(CalendarContract.Events.DESCRIPTION, e.line)
        .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, true)
        .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start)
        .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, start + EgxTime.DAY_MS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

@Composable
fun DividendPage(v: DividendMonth, posture: Posture, tr: Tr, onMonth: (YearMonth) -> Unit, onStock: (String) -> Unit, onAdd: (DividendEntry) -> Unit, onBack: () -> Unit) {
    val c = LocalCalm.current
    val grid: @Composable ColumnScope.() -> Unit = {
        Back(tr.t("Back"), onBack)
        Title(tr.t("Dividends"))
        Lead(tr.t("Each dividend's last day to hold and the day its cash arrives."))
        CalmCard {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("‹", style = Type.name, color = c.ink, modifier = Modifier.clickable(role = Role.Button) { onMonth(v.month.minusMonths(1)) }.padding(horizontal = 12.dp))
                Text(v.title, style = Type.nameSmall, color = c.ink, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                Text("›", style = Type.name, color = c.ink, modifier = Modifier.clickable(role = Role.Button) { onMonth(v.month.plusMonths(1)) }.padding(horizontal = 12.dp))
            }
            Row(Modifier.fillMaxWidth()) { for (w in v.weekdays) Text(w, style = Type.chip, color = c.muted, textAlign = TextAlign.Center, modifier = Modifier.weight(1f)) }
            for (week in v.weeks) Row(Modifier.fillMaxWidth()) {
                for (d in week) Box(Modifier.weight(1f).aspectRatio(1f).padding(2.dp), contentAlignment = Alignment.Center) {
                    if (d != null) {
                        val on = d in v.marked
                        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(100)).background(if (on) c.buyCard else androidx.compose.ui.graphics.Color.Transparent), contentAlignment = Alignment.Center) {
                            Text(tr.digits(d.dayOfMonth.toString()), style = Type.small, color = if (on) c.up else c.ink)
                        }
                    }
                }
            }
        }
    }
    val list: @Composable ColumnScope.() -> Unit = {
        if (v.entries.isEmpty()) CalmCard { Note(tr.t("No dividend dates this month.")) }
        for (e in v.entries) CalmCard {
            Note("${dayText(EgxTime.date(e.day), tr)} · ${e.title}")
            Text(e.name, style = Type.nameSmall, color = c.ink, modifier = Modifier.clickable(role = Role.Button) { onStock(e.code) })
            Note("${e.code} · ${e.line}")
            if (e.runOpen) Chip(tr.t("Dividend run holds it"))
            SecondButton(tr.t("Add to my calendar")) { onAdd(e) }
        }
    }
    Panes(posture, grid, list)
}
