package com.ikverse.signallab.egx.analyst

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** What the analyst is told. The same on every run. */
object EgxAnalystPrompt {
    const val MODEL = "claude-opus-5-5"

    /** A ceiling on one run at API prices, as Claude Code measures it; on the owner's plan it only bounds a runaway run. */
    const val BUDGET_USD = 3.0

    fun system(arabic: Boolean): String = """
        You are the research analyst inside Signal Lab EGX, an Android app one person uses to study daily strategies on the
        Egyptian Exchange. The app runs a few research-backed strategies every evening after the close, turns their signals
        into paper calls for the next session (no real money; the owner may copy a call by hand on Thndr), and keeps an
        append-only paper ledger in Egyptian pounds. Every strategy is judged against three yardsticks over the same sessions:
        cash at the owner's cash rate, the EGX30, and eight random stocks picked the same days. Costs are 0.5% a round trip
        for the 30 busiest stocks and 0.8% for the rest; dividends are paid in after the 5% tax. You help the owner
        understand what is happening and whether the strategies are earning their place. Each evening the app also makes up
        to five technical picks from chart rules (short term with an entry range, two targets and a stop; mid term with an
        entry range and a stop, no targets), followed in their own paper record against the same yardsticks.

        How you work:
        - Use the app's own numbers through your tools (ledger, calls, stock, dividends, research, picks, headlines) rather than memory. Say
          how long and how many trades stand behind any claim. A few months of live record confirms nothing; say "too early
          to say" when that is the truth. Many versions were tried in the research, so some of any good result is luck.
        - Compare with all three yardsticks, and remember that a rise in pounds may only be the pound falling.
        - For news (company announcements, the central bank's rate, the pound), use web search and web fetch, and give the
          source and its date. Keep facts apart from your reading of them. News alone is not a reason for a call.
        - Write for a phone screen: lead with the answer, short paragraphs, plain words, numbers with units.
        - Your tools only read. You cannot place, change or delete anything.
        - Signal Lab EGX is a research tool, not financial advice. Do not tell the owner to risk real money.
        ${if (arabic) "- Answer in plain Egyptian Arabic, as the app's own texts are written. Keep tickers in Latin letters." else "- Answer in English."}
    """.trimIndent()
}

/** One turn of the conversation, kept on the phone. */
data class Turn(val role: String, val text: String)

/** Where a question stands. */
sealed class AskState {
    data object Idle : AskState()
    data class Running(val question: String, val text: String, val working: String?) : AskState()
    data class Done(val question: String, val answer: String) : AskState()
    data class Failed(val question: String, val message: String) : AskState()
}

/**
 * The analyst on the owner's PC: asks Claude Code, through the paired analyst host, with the app's tools bridged back to
 * the phone. It runs in the app's own scope, so swiping to another page does not stop it.
 */
class EgxAnalyst(
    private val pairing: HostPairing,
    private val tools: EgxAnalystTools,
    private val scope: CoroutineScope,
    private val save: suspend (question: String, answer: String, ending: String) -> Unit,
) {
    private val _state = MutableStateFlow<AskState>(AskState.Idle)
    val state: StateFlow<AskState> = _state

    private val history = ArrayList<Turn>()
    private var job: Job? = null

    val conversation: List<Turn> get() = history.toList()

    fun ask(question: String, arabic: Boolean) {
        if (question.isBlank() || _state.value is AskState.Running) return
        job = scope.launch {
            _state.value = AskState.Running(question, "", null)
            val host = pairing.paired()?.let(pairing::client)
            if (host == null) {
                _state.value = AskState.Failed(question, "Pair with the analyst host on your PC first.")
                return@launch
            }
            try {
                val (answer, ending) = run(host, question, arabic)
                if (answer.isNotBlank()) {
                    history += Turn("user", question)
                    history += Turn("assistant", answer)
                    save(question, answer, ending)
                }
                // The host ends with "done", "stopped", "declined", "rounds" (its budget), "cut short" or "failed: …".
                _state.value = if (answer.isNotBlank()) AskState.Done(question, answer) else AskState.Failed(question, "The PC ended the run: $ending.")
            } catch (e: kotlinx.coroutines.CancellationException) {
                _state.value = AskState.Idle
                throw e
            } catch (e: Exception) {
                _state.value = AskState.Failed(question, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun stop() {
        job?.cancel()
    }

    fun newConversation() {
        if (_state.value is AskState.Running) return
        history.clear()
        _state.value = AskState.Idle
    }

    /** The spec the PC host takes: the model, the system prompt, the conversation so far, and the tools. */
    fun spec(question: String, arabic: Boolean): JSONObject = JSONObject()
        .put("model", EgxAnalystPrompt.MODEL)
        .put("system", EgxAnalystPrompt.system(arabic))
        .put("prompt", question)
        .put("history", JSONArray().apply { for (t in history) put(JSONObject().put("role", t.role).put("text", t.text)) })
        .put("tools", JSONArray().apply { for (t in tools.all) put(JSONObject().put("name", t.name).put("description", t.description).put("input_schema", t.schema)) })
        .put("max_budget_usd", EgxAnalystPrompt.BUDGET_USD)

    private suspend fun run(host: HostClient, question: String, arabic: Boolean): Pair<String, String> {
        val said = StringBuilder()
        var runId = ""
        var ending = "stopped"
        var answer = ""
        host.run(spec(question, arabic)) { e ->
            when (e.optString("type")) {
                "run" -> runId = e.optString("id")
                "text" -> {
                    said.append(e.optString("delta"))
                    _state.value = AskState.Running(question, said.toString(), null)
                }
                "working" -> _state.value = AskState.Running(question, said.toString(), e.optString("what"))
                "tool" -> {
                    val name = e.optString("name")
                    _state.value = AskState.Running(question, said.toString(), name)
                    val out = tools.run(name, e.optJSONObject("input") ?: JSONObject())
                    host.answer(runId, e.optString("id"), out.text, out.isError)
                }
                "done" -> {
                    ending = e.optString("ending").ifBlank { "done" }
                    answer = e.optString("answer")
                }
            }
        }
        return answer.ifBlank { said.toString() } to ending
    }
}
