package com.ikverse.signallab.egx.view

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.LayoutDirection
import com.ikverse.signallab.egx.analyst.AskState
import com.ikverse.signallab.egx.analyst.HostProbe
import com.ikverse.signallab.egx.analyst.PairedHost
import com.ikverse.signallab.egx.i18n.Tr
import com.ikverse.signallab.egx.ledger.AnalystAnswerRow
import kotlinx.coroutines.launch

/** The model's light Markdown, made plain for the screen: no bold marks, no heading hashes. */
fun plain(text: String): String = text.lines().joinToString("\n") { it.replace("**", "").replace(Regex("^#{1,6}\\s+"), "") }.trim()

/** Questions to start from, so the owner need not know what to ask. */
fun starterQuestions(tr: Tr): List<String> = listOf(
    tr.t("How are my strategies doing against cash and the EGX30?"),
    tr.t("Explain tomorrow's calls."),
    tr.t("Which dividends are coming, and is there news on them?"),
)

@Composable
fun AnalystPage(
    state: AskState,
    paired: PairedHost?,
    answers: List<AnalystAnswerRow>,
    hasConversation: Boolean,
    posture: Posture,
    tr: Tr,
    onProbe: suspend (String, String) -> HostProbe,
    onConfirm: (HostProbe) -> Unit,
    onForget: () -> Unit,
    onAsk: (String) -> Unit,
    onStop: () -> Unit,
    onNew: () -> Unit,
) {
    val c = LocalCalm.current
    val scope = rememberCoroutineScope()
    var question by remember { mutableStateOf("") }
    val ask: @Composable ColumnScope.() -> Unit = {
        Title(tr.t("Analyst"))
        Lead(tr.t("Ask about your strategies, calls and stocks. Claude answers on your PC, from this phone's own numbers."))
        if (paired == null) PairCard(tr, onProbe, onConfirm, scope)
        else {
            CalmCard {
                OutlinedTextField(question, { question = it }, label = { Text(tr.t("Your question")) }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                if (state is AskState.Running) SecondButton(tr.t("Stop"), onStop)
                else PrimaryButton(tr.t("Ask")) { if (question.isNotBlank()) { onAsk(question.trim()); question = "" } }
                if (hasConversation && state !is AskState.Running) Text(tr.t("New conversation"), style = Type.small, color = c.muted, modifier = Modifier.clickable(role = Role.Button, onClick = onNew))
            }
            if (state is AskState.Idle && !hasConversation) for (s in starterQuestions(tr)) Text("› $s", style = Type.body, color = c.ink, modifier = Modifier.clickable(role = Role.Button) { onAsk(s) })
            when (state) {
                is AskState.Running -> CalmCard(state.question) {
                    Note(state.working?.let { tr.t("Working: %1\$s", it) } ?: tr.t("Thinking on your PC…"))
                    if (state.text.isNotEmpty()) Text(plain(state.text), style = Type.body, color = c.ink)
                }
                is AskState.Done -> CalmCard(state.question) { Text(plain(state.answer), style = Type.body, color = c.ink) }
                is AskState.Failed -> CalmCard(state.question) {
                    Text(tr.t("The analyst could not answer."), style = Type.body, color = c.down)
                    // The host's own words stay as they come, left to right.
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) { Note(state.message) }
                }
                AskState.Idle -> {}
            }
        }
    }
    val past: @Composable ColumnScope.() -> Unit = {
        if (answers.isNotEmpty()) Text(tr.t("Earlier answers"), style = Type.small, color = c.muted)
        for (a in answers.take(20)) {
            var open by remember(a.id) { mutableStateOf(false) }
            CalmCard {
                Text(a.question, style = Type.nameSmall, color = c.ink, modifier = Modifier.clickable(role = Role.Button) { open = !open })
                Note(dayYear(com.ikverse.signallab.egx.data.EgxTime.today(a.at), tr))
                if (open) Text(plain(a.answer), style = Type.body, color = c.ink)
            }
        }
        if (paired != null) CalmCard(tr.t("Your PC")) {
            Kv(tr.t("Address"), paired.address)
            Kv(tr.t("Fingerprint ends"), paired.pin.takeLast(6))
            SecondButton(tr.t("Forget this PC"), onForget)
        }
        CalmCard { Note(tr.t("The analyst only reads. It cannot place, change or delete anything. Not financial advice.")) }
    }
    Panes(posture, ask, past)
}

@Composable
private fun PairCard(tr: Tr, onProbe: suspend (String, String) -> HostProbe, onConfirm: (HostProbe) -> Unit, scope: kotlinx.coroutines.CoroutineScope) {
    val c = LocalCalm.current
    var address by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var probe by remember { mutableStateOf<HostProbe?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    CalmCard(tr.t("Pair with your PC")) {
        Note(tr.t("Start the analyst host on the PC (start-analyst-host.cmd in the tools folder). Type the address and code its window shows."))
        val p = probe
        if (p == null) {
            OutlinedTextField(address, { address = it }, label = { Text(tr.t("Address")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(code, { code = it }, label = { Text(tr.t("Pairing code")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (busy) Note(tr.t("Looking for the PC…"))
            else PrimaryButton(tr.t("Find the PC")) {
                busy = true; error = null
                scope.launch {
                    try { probe = onProbe(address, code) } catch (e: Exception) { error = e.message ?: e.javaClass.simpleName }
                    busy = false
                }
            }
            error?.let { e ->
                Text(tr.t("Could not pair."), style = Type.body, color = c.down)
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) { Note(e) }
            }
        } else {
            Text(tr.t("Does the PC's window show a fingerprint ending in %1\$s?", p.shortFingerprint), style = Type.body, color = c.ink)
            PrimaryButton(tr.t("Yes, pair")) { onConfirm(p); probe = null }
            SecondButton(tr.t("No")) { probe = null }
        }
    }
}
