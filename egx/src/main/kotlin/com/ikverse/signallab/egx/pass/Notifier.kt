package com.ikverse.signallab.egx.pass

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.ikverse.signallab.egx.EgxPrefs
import com.ikverse.signallab.egx.MainActivity
import com.ikverse.signallab.egx.R
import com.ikverse.signallab.egx.data.EgxTime
import com.ikverse.signallab.egx.i18n.Tr
import com.ikverse.signallab.egx.ledger.CallRow
import com.ikverse.signallab.egx.ledger.PaperFillRow
import com.ikverse.signallab.egx.ledger.PickEventRow
import com.ikverse.signallab.engine.egx.PickFamily
import com.ikverse.signallab.engine.egx.EgxStrategies

/** The texts of the evening's notifications, apart from Android, so a test can read them. */
object NotificationTexts {
    /** The one sentence of Today ("Tomorrow: buy two, sell one."), from the calls. */
    fun sentence(calls: List<CallRow>, tr: Tr): String {
        val buys = calls.count { it.action == "buy" }
        val sells = calls.count { it.action == "sell" }
        if (buys == 0 && sells == 0) return if (calls.any { it.action == "keep" }) tr.t("Tomorrow: keep what you hold.") else tr.t("Tomorrow: nothing to do.")
        if (buys == 2 && sells == 1) return tr.t("Tomorrow: buy two, sell one.")
        val parts = listOfNotNull(if (buys > 0) counted("buy", buys, tr) else null, if (sells > 0) counted("sell", sells, tr) else null)
        // Arabic joins the parts with " و" (the table's note); English with " and ".
        return tr.t("Tomorrow: %1\$s.", parts.joinToString(if (tr.isArabic) " و" else " and "))
    }

    /** "buy one", "buy two", "buy 5": Arabic's counted noun changes at 1, 2, 3 to 10, and 11 and more. */
    private fun counted(verb: String, n: Int, tr: Tr): String = when {
        n == 1 -> tr.t("$verb one")
        n == 2 -> tr.t("$verb two")
        n <= 10 || !tr.isArabic -> tr.t("$verb %1\$d", n)
        else -> tr.t("$verb %1\$d (11+)", n)
    }

    fun plan(calls: List<CallRow>, names: (String) -> String, tr: Tr): List<String> = calls.filter { it.action != "keep" }.map { c ->
        val what = if (c.action == "buy") tr.t("Buy %1\$s: %2\$s shares, about %3\$s pounds", names(c.code), tr.count(c.shares), tr.pounds(c.pounds))
        else tr.t("Sell %1\$s: %2\$s shares", names(c.code), tr.count(c.shares))
        val whenText = when (c.timing) {
            "close" -> tr.t("near the close")
            "open" -> tr.t("at the open")
            "wait" -> tr.t("wait a day: it jumped 8% or more")
            else -> ""
        }
        if (whenText.isEmpty()) what else "$what · $whenText"
    }

    /** "Technical picks: 3 short-term, 2 mid-term", or null when the evening made none. */
    fun picksTitle(t: TechnicalResult, tr: Tr): String? {
        val shown = t.lists.filter { it.shows && it.today.isNotEmpty() }
        if (shown.isEmpty()) return null
        val short = shown.firstOrNull { it.list.family == PickFamily.SHORT }?.today?.size ?: 0
        val mid = shown.firstOrNull { it.list.family == PickFamily.MID }?.today?.size ?: 0
        return when {
            short > 0 && mid > 0 -> tr.t("Technical picks: %1\$d short-term, %2\$d mid-term", short, mid)
            short > 0 -> tr.t("Technical picks: %1\$d short-term", short)
            else -> tr.t("Technical picks: %1\$d mid-term", mid)
        }
    }

    /** One line a pick: "COMI · CIB: buy 128.20 to 128.82, stop 125.00". */
    fun pickLines(t: TechnicalResult, names: (String) -> String, tr: Tr): List<String> = t.lists.filter { it.shows }.flatMap { l ->
        l.today.map { p ->
            val lv = p.levels.rounded()
            tr.t("%1\$s: buy %2\$s to %3\$s, stop %4\$s", names(p.code), tr.price(lv.entryLow), tr.price(lv.entryHigh), tr.price(lv.stop))
        }
    }

    fun pickReached(e: PickEventRow, names: (String) -> String, tr: Tr): String = when (e.kind) {
        "target1" -> tr.t("%1\$s reached target 1 at %2\$s: the paper record sells half", names(e.code), tr.price(e.price))
        "target2" -> tr.t("%1\$s reached target 2 at %2\$s: the paper record sells the rest", names(e.code), tr.price(e.price))
        else -> tr.t("%1\$s closed below its stop loss: sell at the open", names(e.code))
    }

    fun reached(f: PaperFillRow, names: (String) -> String, tr: Tr): String = when (f.exit) {
        "target1" -> tr.t("%1\$s reached target 1: half sold at %2\$s", names(f.code), tr.price(f.price))
        "target2" -> tr.t("%1\$s reached target 2: the rest sold at %2\$s", names(f.code), tr.price(f.price))
        else -> tr.t("%1\$s closed below its stop loss: sell at the open", names(f.code))
    }
}

/** Tomorrow's plan in the evening, the month-end list, dividend dates, and a target or stop reached (item 27). */
class Notifier(private val context: Context, private val tr: () -> Tr, private val names: (String) -> String) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    private fun channel(id: String, name: String, importance: Int) {
        manager?.createNotificationChannel(NotificationChannel(id, name, importance))
    }

    fun evening(result: EveningResult, prefs: EgxPrefs) {
        val t = tr()
        channel(PLAN, t.t("Tomorrow's plan"), NotificationManager.IMPORTANCE_DEFAULT)
        channel(LEVELS, t.t("Targets and stops"), NotificationManager.IMPORTANCE_HIGH)
        channel(DIVIDENDS, t.t("Dividend dates"), NotificationManager.IMPORTANCE_DEFAULT)
        val calls = result.calls
        val lines = NotificationTexts.plan(calls, names, t)
        if (lines.isNotEmpty() || result.monthEnd) {
            val title = if (result.monthEnd) t.t("Month-end list") else NotificationTexts.sentence(calls, t)
            val body = if (lines.isEmpty()) NotificationTexts.sentence(calls, t) else lines.joinToString("\n")
            post(PLAN, 1, title, body)
        }
        for ((i, f) in result.levelsReached.withIndex()) {
            if (f.strategy !in prefs.on) continue
            post(LEVELS, 100 + i, EgxStrategies.of(f.strategy)?.name?.let { t.t(it) } ?: "", NotificationTexts.reached(f, names, t))
        }
        val soon = calls.filter { it.lastCumDay != null && it.action != "keep" }
        for ((i, c) in soon.withIndex()) {
            post(DIVIDENDS, 200 + i, t.t("Dividend dates"), t.t("%1\$s: last day with the right %2\$s", names(c.code), EgxTime.date(c.lastCumDay!!).toString()))
        }
    }

    /** The evening's technical picks, and each shown pick that reached a target or closed under its stop. */
    fun picks(t: TechnicalResult) {
        val tr = tr()
        channel(PICKS, tr.t("Technical picks"), NotificationManager.IMPORTANCE_DEFAULT)
        channel(LEVELS, tr.t("Targets and stops"), NotificationManager.IMPORTANCE_HIGH)
        NotificationTexts.picksTitle(t, tr)?.let { title -> post(PICKS, 2, title, NotificationTexts.pickLines(t, names, tr).joinToString("\n")) }
        for ((i, e) in t.reached.withIndex()) {
            val list = EgxStrategies.technicalOf(e.list)?.name?.let { tr.t(it) } ?: ""
            post(LEVELS, 300 + i, list, NotificationTexts.pickReached(e, names, tr))
        }
    }

    private fun post(channel: String, id: Int, title: String, body: String) {
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_signal)
            .setContentTitle(title)
            .setContentText(body.lineSequence().first())
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { manager?.notify(id, n) }
    }

    companion object {
        const val PLAN = "plan"
        const val LEVELS = "levels"
        const val DIVIDENDS = "dividends"
        const val PICKS = "picks"
    }
}
