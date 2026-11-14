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
