package com.ikverse.signallab.egx

import android.content.Context
import com.ikverse.signallab.egx.data.ArabFinanceClient
import com.ikverse.signallab.egx.data.EgxDividendRepository
import com.ikverse.signallab.egx.data.EgxHttp
import com.ikverse.signallab.egx.data.EgxStocks
import com.ikverse.signallab.egx.data.EgxStore
import com.ikverse.signallab.egx.data.EgxSync
import com.ikverse.signallab.egx.data.YahooClient
import com.ikverse.signallab.egx.i18n.Tr
import com.ikverse.signallab.egx.ledger.EgxLedgerDatabase
import com.ikverse.signallab.egx.pass.EveningPass
import com.ikverse.signallab.egx.pass.Notifier
import com.ikverse.signallab.egx.update.AndroidApkInspector
import com.ikverse.signallab.egx.update.AndroidInstallLauncher
import com.ikverse.signallab.egx.update.ApkDownloader
import com.ikverse.signallab.egx.update.AppVersion
import com.ikverse.signallab.egx.update.UpdateChecker
import com.ikverse.signallab.egx.update.UpdateController
import com.ikverse.signallab.egx.update.UpdateMemory
import kotlinx.coroutines.flow.first

/** Everything Signal Lab EGX is made of, built once by the application. */
class EgxGraph(context: Context) {
    private val app = context.applicationContext

    val settings = EgxSettings(app)
    val stocks: EgxStocks = EgxStocks.fromAssets(app)
    private val http = EgxHttp()
    val store = EgxStore(app)
    val sync = EgxSync(store, YahooClient(http), stocks)
    val dividends = EgxDividendRepository(store, ArabFinanceClient(http))
    val ledger = EgxLedgerDatabase.open(app)
    val pass = EveningPass(store, sync, dividends, ledger.dao(), settings)

    private val arabic: Map<String, String> by lazy {
        Tr.parse(app.assets.open("egx/strings_ar.tsv").bufferedReader(Charsets.UTF_8).use { it.readText() })
    }

    /** The language tables as the owner set them. */
    fun tr(): Tr {
        val p = settings.value
        val phone = app.resources.configuration.locales[0]?.language == "ar"
        return Tr(arabic, p.language, phoneIsArabic = phone, arabicDigits = p.arabicDigits)
    }

    /** A stock's name in the app's language, beside its ticker. */
    fun name(code: String): String {
        val s = stocks[code] ?: return code
        val local = if (tr().isArabic) s.nameAr ?: s.nameEn else s.nameEn
        return "$code · $local"
    }

    val notifier = Notifier(app, ::tr, ::name)

    /** The analyst host pairing: the address and certificate in the app's preferences, the code sealed in the no-backup folder. */
    val pairing = com.ikverse.signallab.egx.analyst.HostPairing(
        app.getSharedPreferences("egx-analyst", Context.MODE_PRIVATE),
        java.io.File(app.noBackupFilesDir, com.ikverse.signallab.egx.analyst.HostPairing.FILE_NAME),
        com.ikverse.signallab.egx.analyst.KeystoreCipher(),
    )

    /** What the analyst's tools read: this phone's ledger and prices. */
    private val analystData = object : com.ikverse.signallab.egx.analyst.EgxAnalystData {
        private val dao get() = ledger.dao()
        override suspend fun latestCalls() = dao.latestCalls().first()
        override suspend fun callsSince(day: Long) = dao.callsSince(day)
        override suspend fun days(record: String) = dao.allDays(record).first()
        override suspend fun yardsticks() = dao.yardsticks().first()
        override suspend fun fills() = dao.fills(com.ikverse.signallab.egx.ledger.Records.LEVELS).first()
        override suspend fun copies() = dao.copies().first()
        override suspend fun daily(code: String) = store.daily(code)
        override suspend fun dividends() = store.dividends()
        override suspend fun egx30() = store.egx30().map { it.day to it.close }
        override fun prefs() = settings.value
        override fun name(code: String) = stocks[code]?.let { "${it.nameEn}${it.nameAr?.let { a -> " · $a" } ?: ""}" }
        override fun today() = com.ikverse.signallab.egx.data.EgxTime.today(System.currentTimeMillis())
    }

    val analyst = com.ikverse.signallab.egx.analyst.EgxAnalyst(
        pairing, com.ikverse.signallab.egx.analyst.EgxAnalystTools(analystData),
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default),
    ) { q, a, ending -> ledger.dao().addAnswer(com.ikverse.signallab.egx.ledger.AnalystAnswerRow(at = System.currentTimeMillis(), question = q, answer = a, ending = ending)) }

    /** Updates from the newest release of ikverse/Signal-Lab-EGX on GitHub; Android's installer always asks first. */
    val updates = UpdateController(
        installed = AppVersion.parse(BuildConfig.VERSION_NAME) ?: AppVersion(0, 0, 0),
        source = UpdateChecker(EgxHttp.defaultHttp()),
        downloader = ApkDownloader(EgxHttp.defaultHttp(), AndroidInstallLauncher.downloadDir(app)),
        inspector = AndroidApkInspector(app),
        launcher = AndroidInstallLauncher(app),
        memory = object : UpdateMemory {
            private val prefs = app.getSharedPreferences("egx-updates", Context.MODE_PRIVATE)
            override suspend fun lastCheck() = prefs.getLong("last_check", 0L)
            override suspend fun saveLastCheck(time: Long) = prefs.edit().putLong("last_check", time).apply()
        },
    )
}
