package com.ikverse.signallab.egx.view

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.ikverse.signallab.egx.i18n.Tr

/** The technical picks on Today: the market line, each list with the evening's picks, and the picks still open. */
@Composable
fun PicksSection(v: PicksView, tr: Tr, modifier: Modifier = Modifier, onPick: (PickCardView) -> Unit) {
    val c = LocalCalm.current
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(tr.t("Technical picks"), style = Type.nameSmall, color = c.ink, modifier = Modifier.padding(top = 8.dp))
        if (v.market != null) Note(v.market)
        if (v.day == null) Note(tr.t("The first picks come after the next evening pass."))
        for (l in v.lists) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(l.name, style = Type.body, color = c.ink, modifier = Modifier.weight(1f))
                Chip(l.state)
            }
            if (l.shows && v.day != null && l.cards.isEmpty()) Note(tr.t("No picks this evening."))
            for (card in l.cards) PickTile(card, tr) { onPick(card) }
        }
        if (v.following.isNotEmpty()) {
            Text(tr.t("Still open"), style = Type.small, color = c.muted, modifier = Modifier.padding(top = 4.dp))
            for (card in v.following.take(12)) Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.surface)
                .clickable(role = Role.Button) { onPick(card) }.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(card.name, style = Type.body, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Note("${card.line} · ${card.status}")
            }
        }
        Note(tr.t("Paper picks from chart rules, tested since 2010 and still being tested live. Not financial advice."))
    }
}

/** A compact pick: name, list, and its levels. */
@Composable
private fun PickTile(card: PickCardView, tr: Tr, onOpen: () -> Unit) {
    val c = LocalCalm.current
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(c.buyCard).clickable(role = Role.Button, onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(card.name, style = Type.nameSmall, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(card.line, style = Type.small, color = c.muted, maxLines = 1)
            }
            Text(tr.t("Buy"), style = Type.actionSmall, color = c.up)
        }
        Levels(card.levels)
        if (card.flags.isNotEmpty()) Flags(card.flags)
    }
}

@Composable
private fun Levels(levels: List<PickLevel>) {
    val c = LocalCalm.current
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        for (l in levels) Row(verticalAlignment = Alignment.CenterVertically) {
            val color = when (l.kind) { ChartLevel.Kind.STOP -> c.down; ChartLevel.Kind.ENTRY -> c.up; else -> c.ink }
            Text(l.name, style = Type.small, color = c.muted, modifier = Modifier.weight(1f))
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Row {
                    Text(l.price, style = Type.body, color = color)
                    Text(l.fromEntry, style = Type.small, color = c.muted, textAlign = TextAlign.End, modifier = Modifier.width(64.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Flags(flags: List<String>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { for (f in flags) Chip(f) }
}

/** A pick's own page: its levels, shares, why, the list's record, the company's facts and headlines, and sharing. */
@Composable
fun PickPage(v: PickCardView, posture: Posture, tr: Tr, onBack: () -> Unit, onStock: () -> Unit, onShare: () -> Unit) {
    val c = LocalCalm.current
    val hero: @Composable ColumnScope.() -> Unit = {
        Back(tr.t("Today"), onBack)
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(34.dp)).background(c.buyCard).padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(tr.t("Buy"), style = Type.action, color = c.up)
                Chip(v.listName)
            }
            Text(v.name, style = Type.name, color = c.ink, modifier = Modifier.clickable(role = Role.Button, onClick = onStock))
            Text("${v.line} · ${tr.t("Chart ›")}", style = Type.small, color = c.muted, modifier = Modifier.clickable(role = Role.Button, onClick = onStock))
            Box(Modifier.height(4.dp))
            Levels(v.levels)
            Note(v.exitNote)
        }
        CalmCard(tr.t("Where it stands")) { Text(v.status, style = Type.body, color = c.ink) }
        CalmCard(tr.t("Amount")) { Text(v.shares, style = Type.body, color = c.ink); Note(tr.t("Set the pounds for one pick in Settings.")) }
        PrimaryButton(tr.t("Share as text"), onShare)
    }
    val details: @Composable ColumnScope.() -> Unit = {
        CalmCard(tr.t("Why")) { Text(v.why, style = Type.body, color = c.ink) }
        CalmCard(tr.t("The list's record")) { for (r in v.record) Note(r) }
        CalmCard(tr.t("The company")) {
            for ((k, value) in v.facts) Kv(k, value)
            Note(tr.t("From stockanalysis.com, read once a week. Shown, never used to choose."))
        }
        CalmCard(tr.t("Headlines")) {
            if (v.headlines.isEmpty()) Note(tr.t("No recent headlines found on Arab Finance."))
            for ((h, d) in v.headlines) Column { Text(h, style = Type.body, color = c.ink); if (d.isNotEmpty()) Note(d) }
            if (v.flags.isNotEmpty()) Flags(v.flags)
        }
    }
    if (posture == Posture.FOLDED) Scroll { hero(); details() } else Panes(posture, hero, details)
}

/** Hands the pick's text to whatever the owner shares with. */
fun sharePick(context: Context, text: String, title: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** A list of technical picks on the Learn page. */
@Composable
fun ColumnScope.PickEvidencePage(v: PickEvidenceView, tr: Tr, onBack: (() -> Unit)?) {
    if (onBack != null) Back(tr.t("Strategies"), onBack)
    Chip(tr.t("Testing · on paper"))
    Title(v.name)
    Lead(v.summary)
    CalmCard(tr.t("How it works")) { for (h in v.how) Text(h, style = Type.body, color = LocalCalm.current.ink) }
    CalmCard(tr.t("On paper since its picks started")) { Bars(v.bars, tr); for (r in v.records) Note(r) }
    val r = v.research
    if (r != null) CalmCard(tr.t("In the research: what 1.00 became")) {
        fun f(x: Double) = tr.digits(String.format(java.util.Locale.US, "%.2f", x))
        Note(tr.ltr("2015–2022") + " · " + tr.ltr("2023–2026"))
        Kv(tr.t("This version, with its levels"), "${f(r.old)} · ${f(r.recent)}")
        Kv(tr.t("By the rule alone"), "${f(r.ruleOld)} · ${f(r.ruleRecent)}")
        Kv(tr.t("Version chosen each month from the past only"), "${f(r.learnedOld)} · ${f(r.learnedRecent)}")
        Kv(tr.t("Random stocks, same days and levels"), "${f(r.randomOld)} · ${f(r.randomRecent)}")
        Kv(tr.t("Cash"), "${f(PickEvidence.CASH_OLD)} · ${f(PickEvidence.CASH_RECENT)}")
        Kv(tr.t("The 30 busiest"), "${f(PickEvidence.INDEX_OLD)} · ${f(PickEvidence.INDEX_RECENT)}")
        Note(tr.t("191 stocks, 2010 to 2026, costs of 0.5% to 0.8% a round trip included. Before 2023, choosing the version from the past only barely beat cash."))
    }
    CalmCard(tr.t("Learning each month")) {
        Note(tr.t("On the first evening of each month the list uses the version of its rule that did best over all the history before that month. A list that falls behind cash and the EGX30, or behind nine in ten random picks, after 12 months live is switched off."))
        Kv(tr.t("This month"), v.version)
        for ((m, version) in v.choices) Note("$m · $version")
    }
}

@Composable
fun PickListRow(v: PickEvidenceView, tr: Tr, selected: Boolean, onOpen: () -> Unit) {
    val c = LocalCalm.current
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(c.surface)
            .then(if (selected) Modifier.border(2.dp, c.ink, RoundedCornerShape(28.dp)) else Modifier)
            .clickable(role = Role.Button, onClick = onOpen).padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(v.name, style = Type.nameSmall, color = c.ink, modifier = Modifier.weight(1f))
            Chip(tr.t("Testing · on paper"))
        }
        Note(v.summary)
        Bars(v.bars, tr)
        Text(tr.t("Evidence ›"), style = Type.body, color = c.ink)
    }
}
