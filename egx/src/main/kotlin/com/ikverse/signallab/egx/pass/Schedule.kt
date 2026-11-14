package com.ikverse.signallab.egx.pass

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ikverse.signallab.egx.EgxApplication
import com.ikverse.signallab.egx.data.EgxTime
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * When the evening pass runs: 15:45 Cairo time, Sunday to Thursday, three quarters of an hour after the close (Yahoo's
 * daily candle is usually there by then). If the day's candle is not there yet the pass tries again every half hour,
 * up to [RETRIES] times, until 19:15.
 */
object Schedule {
    val AT: LocalTime = LocalTime.of(15, 45)
    const val RETRY_MINUTES = 30L
    const val RETRIES = 7
    private const val WORK = "egx-evening"

    /** The next evening slot after [nowMs]. Fridays and Saturdays are skipped; a holiday runs and finds no session. */
    fun next(nowMs: Long): Long {
        var t = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(nowMs), EgxTime.CAIRO).with(AT).withSecond(0).withNano(0)
        if (!t.toInstant().isAfter(java.time.Instant.ofEpochMilli(nowMs))) t = t.plusDays(1)
        while (t.dayOfWeek == java.time.DayOfWeek.FRIDAY || t.dayOfWeek == java.time.DayOfWeek.SATURDAY) t = t.plusDays(1)
        return t.toInstant().toEpochMilli()
    }

    fun arm(context: Context, nowMs: Long = System.currentTimeMillis()) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val at = next(nowMs)
        val pi = PendingIntent.getBroadcast(context, 0, Intent(context, EveningReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        if (alarms.canScheduleExactAlarms()) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    }

    /** Runs the pass now (or after [delayMinutes]), as background work that survives the app being closed. */
    fun runNow(context: Context, attempt: Int = 0, delayMinutes: Long = 0) {
        val req = OneTimeWorkRequestBuilder<EveningWorker>()
            .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
            .setInputData(androidx.work.workDataOf("attempt" to attempt))
            .build()
        // A retry gets its own name: replacing the running pass from inside it would cancel it.
        WorkManager.getInstance(context).enqueueUniqueWork(if (attempt == 0) WORK else "$WORK-retry-${attempt % 2}", ExistingWorkPolicy.REPLACE, req)
    }
}

class EveningReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Schedule.runNow(context)
        Schedule.arm(context)
    }
}

class EgxBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Schedule.arm(context)
    }
}

class EveningWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = (applicationContext as EgxApplication).graph
        val attempt = inputData.getInt("attempt", 0)
        when (val outcome = graph.pass.run()) {
            is PassOutcome.Done -> graph.notifier.evening(outcome.result, graph.settings.value)
            PassOutcome.NotYet, is PassOutcome.Failed -> if (attempt < Schedule.RETRIES) Schedule.runNow(applicationContext, attempt + 1, Schedule.RETRY_MINUTES)
            PassOutcome.NoSession -> Unit
        }
        // The quiet daily look for a newer release; it only offers it in Settings, never installs.
        runCatching { graph.updates.checkIfDue() }
        return Result.success()
    }
}
