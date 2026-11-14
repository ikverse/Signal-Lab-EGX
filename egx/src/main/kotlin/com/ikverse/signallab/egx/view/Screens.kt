package com.ikverse.signallab.egx.view

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.ikverse.signallab.egx.EgxPrefs
import com.ikverse.signallab.egx.Language
import com.ikverse.signallab.egx.i18n.Tr
import com.ikverse.signallab.egx.ledger.CallRow
import com.ikverse.signallab.egx.update.UpdateState
import com.ikverse.signallab.engine.egx.EgxStrategies
import com.ikverse.signallab.engine.strategy.Tier

// ---- parts shared by the screens ----

@Composable
fun Scroll(bottomPad: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 22.dp, end = 22.dp, top = 22.dp, bottom = if (bottomPad) 100.dp else 22.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
}

/** One pane, or two side by side when the Fold is open. */
@Composable
fun Panes(posture: Posture, first: @Composable ColumnScope.() -> Unit, second: @Composable ColumnScope.() -> Unit) {
    if (posture == Posture.FOLDED) Scroll { first(); second() }
    else Row(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxHeight()) { Scroll { first() } }
        Box(Modifier.width(1.dp).fillMaxHeight().background(LocalCalm.current.line))
        Box(Modifier.weight(1f).fillMaxHeight()) { Scroll { second() } }
    }
}

@Composable
fun CalmCard(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val c = LocalCalm.current
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(c.surface).padding(horizontal = 18.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (title != null) Text(title, style = Type.small, color = c.muted)
        content()
    }
}

@Composable
fun Kv(k: String, v: String) {
    val c = LocalCalm.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(k, style = Type.body, color = c.ink, modifier = Modifier.weight(1f))
        Text(v, style = Type.body.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = c.ink)
    }
}

@Composable
fun Note(text: String) = Text(text, style = Type.small, color = LocalCalm.current.muted)

@Composable
fun Title(text: String) = Text(text, style = Type.title, color = LocalCalm.current.ink)

@Composable
fun Lead(text: String) = Text(text, style = Type.lead, color = LocalCalm.current.ink)

@Composable
fun Back(text: String, onBack: () -> Unit) = Text("‹ $text", style = Type.body, color = LocalCalm.current.muted, modifier = Modifier.clickable(role = Role.Button, onClick = onBack))

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit) {
    val c = LocalCalm.current
    Text(text, style = Type.body, color = c.actionInk, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(100)).background(c.action).clickable(role = Role.Button, onClick = onClick).padding(14.dp))
}

@Composable
fun SecondButton(text: String, onClick: () -> Unit) {
    val c = LocalCalm.current
    Text(text, style = Type.body, color = c.ink, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(100)).border(1.5.dp, c.ink, RoundedCornerShape(100)).clickable(role = Role.Button, onClick = onClick).padding(12.5.dp))
}

/** Yardstick bars. Charts stay left to right in both languages (item 39). */
@Composable
fun Bars(rows: List<BarRow>, tr: Tr) {
    val c = LocalCalm.current
    if (rows.all { it.value == null }) {
        Note(tr.t(Tr.NOT_YET))
        return
    }
    val top = rows.mapNotNull { it.value?.let { v -> kotlin.math.abs(v) } }.maxOrNull()?.takeIf { it > 0 } ?: 1.0
    val opacity = listOf(1f, 0.3f, 0.55f, 0.42f)
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            for ((i, r) in rows.withIndex()) Row(verticalAlignment = Alignment.CenterVertically) {
                Text(r.label, style = Type.small.copy(textDirection = TextDirection.Content), color = if (r.mine) c.ink else c.muted, modifier = Modifier.width(112.dp), maxLines = 1)
                Box(Modifier.weight(1f).height(7.dp)) {
                    val share = ((r.value?.let { v -> kotlin.math.abs(v) } ?: 0.0) / top).toFloat().coerceIn(0.02f, 1f)
                    Box(Modifier.fillMaxWidth(share).height(7.dp).clip(RoundedCornerShape(7.dp)).background(c.up.copy(alpha = opacity[i % 4])))
                }
                Text(signed(r.value, tr), style = Type.small, color = if ((r.value ?: 0.0) < 0) c.down else c.ink, textAlign = TextAlign.End, modifier = Modifier.width(48.dp))
            }
        }
    }
}

@Composable
fun Chip(text: String) {
    val c = LocalCalm.current
    Text(text, style = Type.small, color = c.ink, modifier = Modifier.clip(RoundedCornerShape(100)).border(1.dp, c.line, RoundedCornerShape(100)).background(c.surface).padding(horizontal = 10.dp, vertical = 3.dp))
}

// ---- the call page (item 31) ----

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallPage(v: CallView, posture: Posture, tr: Tr, onBack: () -> Unit, onCopyAndOpen: () -> Unit, onTook: (Double, Long) -> Unit, onStock: () -> Unit = {}) {
    val c = LocalCalm.current
    var sheet by remember { mutableStateOf(false) }
    val hero: @Composable ColumnScope.() -> Unit = {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Back(tr.t("Today"), onBack) }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(34.dp)).background(if (v.buy) c.buyCard else c.sellCard).padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(v.action, style = Type.action, color = if (v.buy) c.up else c.down)
                Chip(v.strategy)
            }
            Text(v.name, style = Type.name, color = c.ink, modifier = Modifier.clickable(role = Role.Button, onClick = onStock))
            Text("${v.call.code} · ${v.closeLine} · ${tr.t("Chart ›")}", style = Type.small, color = c.muted, modifier = Modifier.clickable(role = Role.Button, onClick = onStock))
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { for (chip in v.chips) Chip(chip) }
        }
        if (v.call.action != "keep") {
            PrimaryButton(tr.t("Copy amount and open Thndr"), onCopyAndOpen)
            SecondButton(tr.t("I took it")) { sheet = true }
        }
        if (v.whenText.isNotEmpty()) CalmCard(tr.t("When to place it")) { Text(v.whenText, style = Type.body, color = c.ink) }
    }
    val levels: @Composable ColumnScope.() -> Unit = {
        if (v.levels.isNotEmpty()) CalmCard(tr.t("The four levels")) {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    for ((price, name, line) in v.levels) Row(verticalAlignment = Alignment.CenterVertically) {
                        val color = when (name) { tr.t("Stop loss") -> c.down; tr.t("Entry range") -> c.up; else -> c.ink }
                        Text(tr.price(price), style = Type.nameSmall, color = color, modifier = Modifier.width(72.dp), textAlign = TextAlign.End)
                        Box(Modifier.padding(horizontal = 12.dp).width(12.dp).height(12.dp).clip(RoundedCornerShape(6.dp)).border(3.dp, color, RoundedCornerShape(6.dp)))
                        CompositionLocalProvider(LocalLayoutDirection provides if (tr.isArabic) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                            Column(Modifier.weight(1f)) { Text(name, style = Type.body, color = c.ink); Note(line) }
                        }
                    }
                }
            }
            Note(v.levelsNote)
        }
    }
    val details: @Composable ColumnScope.() -> Unit = {
        CalmCard(tr.t("Amount")) { for ((k, value) in v.amount) Kv(k, value); Note(v.amountNote) }
        if (v.dividend.isNotEmpty()) CalmCard(tr.t("Dividend dates")) { for ((k, value) in v.dividend) Kv(k, value); v.dividendNote?.let { Note(it) } }
        CalmCard(tr.t("Why")) { Text(v.reason, style = Type.body, color = c.ink) }
    }
    if (posture == Posture.FOLDED) Scroll { hero(); levels(); details() }
    else Panes(posture, { hero(); details() }, { levels() })
    if (sheet) {
        var price by remember { mutableStateOf(tr.price(v.call.close)) }
        var shares by remember { mutableStateOf(v.call.shares.toString()) }
        ModalBottomSheet(onDismissRequest = { sheet = false }, containerColor = c.surface) {
            Column(Modifier.padding(horizontal = 22.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(tr.t("I took it"), style = Type.name, color = c.ink)
                OutlinedTextField(price, { price = it }, label = { Text(tr.t("Price I paid")) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(shares, { shares = it }, label = { Text(tr.t("Shares")) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                val p = price.replace(',', '.').toDoubleOrNull()
                if (p != null && p > 0) Note(tr.t("Against the paper call: %1\$s", signed(-copyGap(v.call, p), tr)))
                PrimaryButton(tr.t("Save to My copies")) {
                    val n = shares.toLongOrNull()
                    if (p != null && p > 0 && n != null && n > 0) { onTook(p, n); sheet = false }
                }
                SecondButton(tr.t("Cancel")) { sheet = false }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

// ---- Strategies and an evidence page (item 32) ----

@Composable
fun StrategiesPage(views: List<StrategyView>, posture: Posture, tr: Tr, chosen: String?, onChoose: (String?) -> Unit) {
    val c = LocalCalm.current
    val list: @Composable ColumnScope.() -> Unit = {
        Title(tr.t("Strategies"))
        Lead(tr.t("Three give calls on Today. Two are recorded and watched. One runs as a control."))
        for (tier in listOf(Tier.PROMISING, Tier.WATCH, Tier.TESTING)) {
            val group = views.filter { if (tier == Tier.PROMISING) it.tier.makesCalls else it.tier == tier }
            if (group.isEmpty()) continue
            Text(tierLine(tier, tr), style = Type.small, color = c.muted, modifier = Modifier.padding(top = 8.dp, start = 4.dp))
            for (s in group) Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(c.surface)
                    .then(if (s.strategy.id == chosen && posture != Posture.FOLDED) Modifier.border(2.dp, c.ink, RoundedCornerShape(28.dp)) else Modifier)
                    .clickable(role = Role.Button) { onChoose(s.strategy.id) }.padding(horizontal = 18.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(s.name, style = Type.nameSmall, color = c.ink, modifier = Modifier.weight(1f))
                    Chip(tierLine(s.tier, tr).substringBefore(" · "))
                }
                Note(s.summary)
                Bars(s.bars, tr)
                Text(tr.t("Evidence ›"), style = Type.body, color = c.ink)
            }
        }
        CalmCard { Note(tr.t("A strategy moves up or down only by its own record against the three yardsticks: cash, the EGX30, and eight random stocks picked the same days. %1\$d versions have been tried in all, so some of any good result is luck.", EgxStrategies.triedCount)) }
    }
    val pick = views.firstOrNull { it.strategy.id == chosen } ?: views.first()
    when {
        posture == Posture.FOLDED && chosen != null -> Scroll { EvidencePage(pick, tr) { onChoose(null) } }
        posture == Posture.FOLDED -> Scroll(content = list)
        else -> Panes(posture, list) { EvidencePage(pick, tr, null) }
    }
}

@Composable
fun ColumnScope.EvidencePage(s: StrategyView, tr: Tr, onBack: (() -> Unit)?) {
    if (onBack != null) Back(tr.t("Strategies"), onBack)
    Chip(s.tierLine)
    Title(s.name)
    Lead(s.summary)
    CalmCard(tr.t("On paper since the ledger started")) {
        Bars(s.bars, tr)
        Note(tr.t("%1\$d calls · holds %2\$d stocks now", s.calls, s.holds))
    }
    val r = Evidence.BY_ID[s.strategy.id]
    if (r != null) {
        CalmCard(tr.t("In the research: what 1.00 became")) {
            Note(tr.ltr("2015–2022"))
            Kv(s.name, tr.digits(String.format(java.util.Locale.US, "%.2f", r.old)))
            Kv(tr.t("Cash"), tr.digits(String.format(java.util.Locale.US, "%.2f", Evidence.CASH_OLD)))
            Kv(tr.t("The 30 busiest"), tr.digits(String.format(java.util.Locale.US, "%.2f", Evidence.MARKET_OLD)))
            Note(tr.ltr("2023–2026"))
            Kv(s.name, tr.digits(String.format(java.util.Locale.US, "%.2f", r.recent)))
            Kv(tr.t("Cash"), tr.digits(String.format(java.util.Locale.US, "%.2f", Evidence.CASH_RECENT)))
            Kv("EGX30", tr.digits(String.format(java.util.Locale.US, "%.2f", Evidence.EGX30_RECENT)))
            Note(tr.t("191 stocks, 2010 to 2026, costs included. Each year used only settings that had worked in earlier years."))
        }
        if (r.levelsOld != null && r.levelsRecent != null) CalmCard(tr.t("Selling at the targets")) {
            Kv(tr.t("With targets 5 and 10"), tr.digits(String.format(java.util.Locale.US, "%.2f · %.2f", r.levelsOld, r.levelsRecent)))
            Note(tr.t("Selling at the targets cost about half the gain in the research. You chose the targets on 14 Nov; the ledger keeps both records."))
        }
    }
    CalmCard(tr.t("What to keep in mind")) {
        Note(tr.t("Prices come from Yahoo's free feed and were cleaned. Delisted stocks are missing. Costs are 0.5% a round trip for the 30 busiest stocks and 0.8% for the rest."))
    }
}

// ---- Ledger (item 33) ----

@Composable
fun LedgerPage(v: LedgerView, posture: Posture, tr: Tr, onStock: (String) -> Unit = {}, real: RealTermsView? = null) {
    val c = LocalCalm.current
    var rule by remember { mutableStateOf(false) }
    var measure by remember { mutableStateOf(Measure.POUNDS) }
    val a: @Composable ColumnScope.() -> Unit = {
        if (v.since.isNotEmpty()) Note(v.since)
        Title(tr.t("Ledger"))
        Lead(v.lead)
        CalmCard(tr.t("Paper account")) {
            Text(tr.t("EGP %1\$s", tr.pounds(if (rule) v.byRule else v.account)), style = Type.tile, color = c.ink, modifier = Modifier.padding(bottom = 4.dp))
            if (v.started && real != null && real.known) {
                Note(tr.t("Shown in"))
                Seg(listOf(tr.t("Pounds"), tr.t("Dollars"), tr.t("Gold")), measure.ordinal) { measure = Measure.entries[it] }
                Bars(real.bars.getValue(measure), tr)
            } else if (v.started) Bars(v.accountBars, tr)
        }
        if (v.started && real != null && real.known) CalmCard(tr.t("In real terms")) {
            for ((k, value) in real.became) Kv(k, value)
            Note(real.note)
        }
        if (v.started) CalmCard(tr.t("Two records")) {
            Seg(listOf(tr.t("With targets"), tr.t("By the rule alone")), if (rule) 1 else 0) { rule = it == 1 }
            Kv(tr.t("With targets"), tr.t("EGP %1\$s", tr.pounds(v.withTargets)))
            Kv(tr.t("By the rule alone"), tr.t("EGP %1\$s", tr.pounds(v.byRule)))
            Note(v.targetsCost)
        }
        if (v.started) Text(tr.t("By strategy"), style = Type.small, color = c.muted)
        if (v.started) for (s in v.byStrategy) CalmCard { Kv(s.name, s.equity?.let { tr.t("EGP %1\$s", tr.pounds(it)) } ?: "–"); Bars(s.bars, tr) }
    }
    val b: @Composable ColumnScope.() -> Unit = {
        if (v.started) CalmCard(tr.t("Watched, not in the account")) { for (s in v.watched) Kv(s.name, signed(s.bars.first().value, tr)) }
        CalmCard(tr.t("My copies")) {
            Text(v.copiesLine, style = Type.body, color = c.ink)
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Column { for (cp in v.copies.take(20)) Box(Modifier.clickable(role = Role.Button) { onStock(cp.code) }) { Kv("${dayText(com.ikverse.signallab.egx.data.EgxTime.date(cp.day), tr)} · ${cp.code} · ${if (cp.buy) tr.t("Buy") else tr.t("Sell")}", "${tr.count(cp.shares)} @ ${tr.price(cp.price)}") } }
            }
        }
        if (v.started) CalmCard(tr.t("Costs")) {
            Kv(tr.t("Paid so far"), tr.t("EGP %1\$s", tr.pounds(v.costsPaid)))
            Kv(tr.t("Account at double costs"), tr.t("EGP %1\$s", tr.pounds(v.atDouble)))
            Kv(tr.t("Dividends paid in, after 5% tax"), tr.t("EGP %1\$s", tr.pounds(v.dividends)))
        }
    }
    Panes(posture, a, b)
}

@Composable
fun Seg(options: List<String>, selected: Int, onPick: (Int) -> Unit) {
    val c = LocalCalm.current
    // Every option is as tall as the tallest, with its text in the middle, so a longer label never makes the control lopsided.
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).clip(RoundedCornerShape(100)).border(1.dp, c.line, RoundedCornerShape(100)).padding(4.dp)) {
        for ((i, o) in options.withIndex()) Box(
            Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(100)).background(if (i == selected) c.action else androidx.compose.ui.graphics.Color.Transparent)
                .clickable(role = Role.RadioButton) { onPick(i) }.padding(horizontal = 6.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) { Text(o, style = Type.chip, textAlign = TextAlign.Center, color = if (i == selected) c.actionInk else c.muted, maxLines = 1) }
    }
}

// ---- Settings (items 26 and 40) ----

@Composable
fun SettingsPage(p: EgxPrefs, posture: Posture, tr: Tr, onChange: ((EgxPrefs) -> EgxPrefs) -> Unit, onRunNow: () -> Unit,
    update: UpdateState = UpdateState.Idle(null), version: String = "", onUpdate: () -> Unit = {}) {
    val c = LocalCalm.current
    val a: @Composable ColumnScope.() -> Unit = {
        Title(tr.t("Settings"))
        CalmCard(tr.t("Money I trade with")) {
            NumberField(String.format(java.util.Locale.US, "%.0f", p.money), tr.t("EGP")) { v -> v.toDoubleOrNull()?.takeIf { it > 0 }?.let { m -> onChange { it.copy(money = m) } } }
            Note(tr.t("Every call's pounds and shares come from this, split equally among the strategies that are on: %1\$s each.", tr.pounds(p.money / maxOf(1, p.on.size))))
        }
        CalmCard(tr.t("Cash rate")) {
            NumberField(String.format(java.util.Locale.US, "%.1f", 100 * p.cashRate), tr.t("% a year")) { v -> v.toDoubleOrNull()?.takeIf { it in 0.0..100.0 }?.let { r -> onChange { it.copy(cashRate = r / 100) } } }
            Note(tr.t("Money that is not in a stock earns this on paper. Set it to your money-market fund's rate."))
        }
        CalmCard(tr.t("Strategies that give calls")) {
            for (s in EgxStrategies.all.filter { !it.silent }) {
                val allowed = s.launchTier.makesCalls
                Toggle(tr.t(s.name), if (allowed) tierLine(Tier.PROMISING, tr) else tr.t("Watch: can be turned on once its record earns it"), s.id in p.on, allowed) { on ->
                    onChange { it.copy(on = if (on) it.on + s.id else it.on - s.id) }
                }
            }
        }
    }
    val b: @Composable ColumnScope.() -> Unit = {
        CalmCard(tr.t("Language")) {
            Seg(listOf(tr.t("Arabic"), tr.t("English"), tr.t("Follow the phone")), when (p.language) { Language.ARABIC -> 0; Language.ENGLISH -> 1; else -> 2 }) { i ->
                onChange { it.copy(language = listOf(Language.ARABIC, Language.ENGLISH, Language.FOLLOW_PHONE)[i]) }
            }
            Toggle(tr.t("Arabic-Indic digits"), tr.t("Show ١٢٣ instead of 123"), p.arabicDigits, true) { on -> onChange { it.copy(arabicDigits = on) } }
        }
        CalmCard(tr.t("Costs")) {
            Toggle(tr.t("Also show everything at double costs"), tr.t("Fees and stamp duty 0.16% a round trip, plus the spread: 0.5% in all for the 30 busiest, 0.8% for the rest."), p.doubleCosts, true) { on -> onChange { it.copy(doubleCosts = on) } }
            Kv(tr.t("Free orders a month"), tr.digits("$FREE_ORDERS"))
        }
        CalmCard(tr.t("Days the exchange is closed")) {
            Note(tr.t("Fixed public holidays are known. Add the Eids, Sham el-Nessim and any moved holiday, as yyyy-mm-dd, one a line."))
            var text by remember(p.holidays) { mutableStateOf(p.holidays.sorted().joinToString("\n")) }
            OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth(), minLines = 2)
            SecondButton(tr.t("Save holidays")) {
                val days = text.lines().mapNotNull { runCatching { java.time.LocalDate.parse(it.trim()) }.getOrNull() }.toSet()
                onChange { it.copy(holidays = days) }
            }
        }
        UpdatesCard(update, version, tr, onUpdate)
        SecondButton(tr.t("Run the evening pass now"), onRunNow)
        CalmCard { Note(tr.t("Signal Lab EGX is a research tool. Every call is on paper; no real money is traded by the app. Not financial advice.")) }
    }
    Panes(posture, a, b)
}

@Composable
private fun NumberField(value: String, unit: String, onDone: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    OutlinedTextField(text, { text = it; onDone(it.replace(',', '.')) }, suffix = { Text(unit) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
}

@Composable
private fun Toggle(name: String, line: String, on: Boolean, enabled: Boolean, onSet: (Boolean) -> Unit) {
    val c = LocalCalm.current
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(name, style = Type.body, color = if (enabled) c.ink else c.muted); Note(line) }
        Switch(on, onSet, enabled = enabled)
    }
}

// ---- the month-end list (item 34) ----

@Composable
fun MonthEndPage(v: MonthEndView, posture: Posture, tr: Tr, names: (String) -> String, onBack: () -> Unit, onStock: (String) -> Unit = {}, onPlaced: (CallRow) -> Unit) {
    val c = LocalCalm.current
    val head: @Composable ColumnScope.() -> Unit = {
        Back(tr.t("Today"), onBack)
        Title(v.title)
        CalmCard(tr.t("Free orders")) {
            Text(tr.digits("${v.ordersUsed} / $FREE_ORDERS"), style = Type.tile, color = c.ink)
            Note(tr.t("This list uses %1\$d of them.", v.listOrders))
        }
        CalmCard(tr.t("Money")) {
            Kv(tr.t("The sells bring"), tr.t("EGP %1\$s", tr.pounds(v.bring)))
            Kv(tr.t("The buys need"), tr.t("EGP %1\$s", tr.pounds(v.need)))
            Note(tr.t("Sell first at the open; buy near the close the same day."))
        }
    }
    val lists: @Composable ColumnScope.() -> Unit = {
        @Composable
        fun row(call: CallRow) {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(if (call.action == "buy") c.buyCard else c.sellCard).padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).clickable(role = Role.Button) { onStock(call.code) }) {
                    Text(names(call.code), style = Type.nameSmall, color = c.ink)
                    Note("${call.code} · ${EgxStrategies.of(call.strategy)?.name?.let { tr.t(it) } ?: ""} · ${tr.t("%1\$d shares", call.shares)} · ${tr.t("EGP %1\$s", tr.pounds(call.pounds))}")
                }
                Text(if (call.action == "buy") tr.t("Buy") else tr.t("Sell"), style = Type.actionSmall, color = if (call.action == "buy") c.up else c.down,
                    modifier = Modifier.clickable(role = Role.Checkbox) { onPlaced(call) })
            }
        }
        if (v.sells.isNotEmpty()) Note(tr.t("Sell these"))
        for (s in v.sells) row(s)
        if (v.buys.isNotEmpty()) Note(tr.t("Buy these"))
        for (b in v.buys) row(b)
        if (v.keeps.isNotEmpty()) CalmCard(tr.t("Keep · no order needed")) { for (k in v.keeps) Kv(names(k.code), k.code) }
    }
    Panes(posture, head, lists)
}

// ---- updates from GitHub ----

/** What the Updates card says for each state of the updater, and what its button does. */
fun updateTexts(state: UpdateState, tr: Tr): Triple<String, String?, String?> = when (state) {
    is UpdateState.Idle, is UpdateState.UpToDate -> Triple(
        if (state is UpdateState.UpToDate) tr.t("This is the newest version.") else tr.t("Looks for a newer version on GitHub once a day."),
        null, tr.t("Check for updates"))
    UpdateState.Checking -> Triple(tr.t("Checking GitHub…"), null, null)
    is UpdateState.Available -> Triple(tr.t("Version %1\$s is ready.", state.release.version.toString()), state.release.notes.take(400), tr.t("Install"))
    is UpdateState.Downloading -> Triple(tr.t("Downloading version %1\$s…", state.release.version.toString()), null, null)
    is UpdateState.NeedsPermission -> Triple(tr.t("Allow installs from Signal Lab EGX in Android's settings, then come back and tap Install."), null, tr.t("Install"))
    is UpdateState.Handed -> Triple(tr.t("Android's installer is open. It asks you before it installs."), null, tr.t("Install"))
    is UpdateState.Failed -> Triple(tr.t("The update could not be completed."), state.message, if (state.release != null) tr.t("Install") else tr.t("Check for updates"))
}

@Composable
fun UpdatesCard(state: UpdateState, version: String, tr: Tr, onTap: () -> Unit) {
    val c = LocalCalm.current
    val (line, detail, button) = updateTexts(state, tr)
    CalmCard(tr.t("Updates")) {
        Kv(tr.t("Installed version"), tr.digits(version))
        Text(line, style = Type.body, color = c.ink)
        // GitHub's own words (versions, error codes) stay as they come; they are left to right.
        if (detail != null) CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) { Note(detail) }
        if (button != null) SecondButton(button, onTap)
    }
}
