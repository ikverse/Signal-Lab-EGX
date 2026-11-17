package com.ikverse.signallab.egx

import android.content.Context
import android.content.SharedPreferences
import com.ikverse.signallab.engine.Markets
import com.ikverse.signallab.engine.egx.EgxStrategies
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalDate

/** The app's language: Arabic, English, or whatever the phone uses. */
enum class Language { FOLLOW_PHONE, ARABIC, ENGLISH }

/** What the owner sets (docs/egx-plan.md parts 4 to 6). */
data class EgxPrefs(
    /** "Money I trade with", in pounds: what the calls' amounts are worked out from. */
    val money: Double = 100_000.0,
    /** What idle money earns a year after tax. */
    val cashRate: Double = Markets.EGX.cashRate,
    /** The strategies that hold part of the paper account and make calls (the three on Today, to start). */
    val on: Set<String> = DEFAULT_ON,
    val language: Language = Language.FOLLOW_PHONE,
    val arabicDigits: Boolean = false,
    /** Show every result at double the costs. */
    val doubleCosts: Boolean = false,
    /** Days the exchange is closed that the fixed table does not know: the Eids, Sham el-Nessim, a moved holiday. */
    val holidays: Set<LocalDate> = emptySet(),
    /** The session the paper ledger started on; null before the first evening pass. */
    val liveStart: Long? = null,
    /** Pounds for one technical pick: what its card's shares are worked out from. Never part of "money I trade with". */
    val pickAmount: Double = 10_000.0,
    /** The lists of technical picks shown on Today (both, to start). */
    val picksOn: Set<String> = PICKS_ON,
    /** The session the technical picks' ledger started on; null before their first evening. */
    val picksStart: Long? = null,
) {
    companion object {
        val DEFAULT_ON: Set<String> = EgxStrategies.all.filter { it.launchTier.makesCalls && !it.silent }.map { it.id }.toSet()
        val PICKS_ON: Set<String> = EgxStrategies.technical.map { it.id }.toSet()
    }
}

/** The settings, kept in the app's own preferences and offered as a flow the screens read. */
class EgxSettings(private val prefs: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences("egx-settings", Context.MODE_PRIVATE))

    private val state = MutableStateFlow(read())
    val flow: StateFlow<EgxPrefs> get() = state
    val value: EgxPrefs get() = state.value

    private fun read(): EgxPrefs {
        val d = EgxPrefs()
        return EgxPrefs(
            money = prefs.getString("money", null)?.toDoubleOrNull() ?: d.money,
            cashRate = prefs.getString("cash_rate", null)?.toDoubleOrNull() ?: d.cashRate,
            on = prefs.getStringSet("on", null)?.toSet() ?: d.on,
            language = prefs.getString("language", null)?.let { runCatching { Language.valueOf(it) }.getOrNull() } ?: d.language,
            arabicDigits = prefs.getBoolean("arabic_digits", d.arabicDigits),
            doubleCosts = prefs.getBoolean("double_costs", d.doubleCosts),
            holidays = prefs.getStringSet("holidays", null)?.mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }?.toSet() ?: d.holidays,
            liveStart = if (prefs.contains("live_start")) prefs.getLong("live_start", 0) else null,
            pickAmount = prefs.getString("pick_amount", null)?.toDoubleOrNull() ?: d.pickAmount,
            picksOn = prefs.getStringSet("picks_on", null)?.toSet() ?: d.picksOn,
            picksStart = if (prefs.contains("picks_start")) prefs.getLong("picks_start", 0) else null,
        )
    }

    fun update(change: (EgxPrefs) -> EgxPrefs) {
        val next = change(state.value)
        prefs.edit()
            .putString("money", next.money.toString())
            .putString("cash_rate", next.cashRate.toString())
            .putStringSet("on", next.on)
            .putString("language", next.language.name)
            .putBoolean("arabic_digits", next.arabicDigits)
            .putBoolean("double_costs", next.doubleCosts)
            .putStringSet("holidays", next.holidays.map { it.toString() }.toSet())
            .apply { if (next.liveStart != null) putLong("live_start", next.liveStart) else remove("live_start") }
            .putString("pick_amount", next.pickAmount.toString())
            .putStringSet("picks_on", next.picksOn)
            .apply { if (next.picksStart != null) putLong("picks_start", next.picksStart) else remove("picks_start") }
            .apply()
        state.value = next
    }
}
