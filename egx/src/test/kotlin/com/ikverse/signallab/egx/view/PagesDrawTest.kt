package com.ikverse.signallab.egx.view

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.ikverse.signallab.egx.EgxPrefs
import com.ikverse.signallab.egx.analyst.AskState
import com.ikverse.signallab.egx.analyst.HostProbe
import com.ikverse.signallab.egx.data.EgxTime
import com.ikverse.signallab.egx.i18n.Tr
import com.ikverse.signallab.egx.ledger.PaperDayRow
import com.ikverse.signallab.egx.ledger.PaperFillRow
import com.ikverse.signallab.egx.ledger.Records
import com.ikverse.signallab.egx.ledger.YardstickRow
import com.ikverse.signallab.engine.egx.EgxStrategies
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Every page under the pill menu is drawn in every posture, with a ledger that has started. A page that cannot be
 * measured (as the segmented switch once could not) crashes the app the moment it is swiped to; here it fails a test.
 */
@RunWith(RobolectricTestRunner::class)
class PagesDrawTest {
    @get:Rule val rule = createComposeRule()

    private val thu = EgxTime.day(2026, 11, 12)
    private val en = Tr.ENGLISH
    private val prefs = EgxPrefs(money = 100_000.0, liveStart = EgxTime.day(2026, 2, 1))
    private val on = EgxPrefs.DEFAULT_ON
    private val levels = on.map { PaperDayRow(it, Records.LEVELS, thu, 43_800.0, 0.5, 100.0) }
    private val days = mapOf(
        Records.LEVELS to levels,
        Records.RULE to on.map { PaperDayRow(it, Records.RULE, thu, 46_300.0, 0.5, 0.0) },
        Records.LEVELS_DOUBLE to on.map { PaperDayRow(it, Records.LEVELS_DOUBLE, thu, 43_000.0, 0.5, 0.0) },
    )
    private val fills = listOf(PaperFillRow(EgxStrategies.calm.id, Records.LEVELS, thu, "COMI", true, 10.0, 100.0, "", 2.5))
    private val yard = listOf(YardstickRow(EgxStrategies.calm.id, thu, 1.16, 1.23, 1.0, 1.1, 1.2))

    @Test fun todayDraws() = drawInEveryPosture(0)
    @Test fun strategiesDraws() = drawInEveryPosture(1)
    @Test fun ledgerDraws() = drawInEveryPosture(2)
    @Test fun analystDraws() = drawInEveryPosture(3)
    @Test fun settingsDraws() = drawInEveryPosture(4)

    /** One composition per test (the rule allows one), switched through folded, unfolded and tabletop. */
    private fun drawInEveryPosture(page: Int) {
        val posture = mutableStateOf(Posture.FOLDED)
        rule.setContent {
            BoardTheme(dark = false) {
                BoardPage {
                    val p = posture.value
                    when (page) {
                        0 -> TodayScreen(todayView(emptyList(), levels, yard, fills, prefs, emptyList(), en, { it to it }, EgxTime.date(thu)),
                            p, en, onPlaced = {}, onOpen = {}, bottom = PaddingValues(0.dp))
                        1 -> StrategiesPage(strategyViews(levels, yard, fills, en), p, en, null) {}
                        2 -> LedgerPage(ledgerView(days, yard, mapOf(Records.LEVELS to fills), emptyList(), 14, prefs, en), p, en)
                        3 -> AnalystPage(AskState.Idle, null, emptyList(), false, p, en,
                            onProbe = { a, c -> HostProbe(a, c, "", null) }, onConfirm = {}, onScan = {}, onForget = {},
                            onAsk = {}, onStop = {}, onNew = {})
                        else -> SettingsPage(prefs, p, en, onChange = {}, onRunNow = {})
                    }
                }
            }
        }
        for (each in Posture.entries) {
            rule.runOnIdle { posture.value = each }
            rule.waitForIdle()
        }
    }
}
