# Engine overhaul: running log

What changed and why, in the order it happened. One entry per commit on `feature/engine-overhaul`. Phases follow the mission: 1 comprehension, 2 engine audit and fixes, 3 pattern research and validation, 4 Pattern Lab and UX, 5 Claude integration.

## Phase 1: comprehension (read-only)

### 2026-10-14: `docs/engine-audit.md`

- Added the architecture and engine audit. No app code changed.
- Branch `feature/engine-overhaul` created from `main` at `acb96f3` (Release 1.7.0).
- Engine tests run on this machine: 153 passed, 0 failed.
- Found while reading, carried into Phase 2 as candidates (not yet fixed): sticky scan problem line (`ScanHealth.lastProblem`), fast charts scanned by the hourly alarm when following is off, stale lab-budget defaults, unused `forwardOnly` flag, full-window recomputation every pass, live default cost of 0.20 % versus the 0.25 %/0.40 % stated in `CLAUDE.md`, no out-of-sample evaluation anywhere.

## Signal Lab 2 rebuild (branch `v2`)

The five-phase overhaul was replaced on 2026-10-16 by a from-scratch rebuild; plan in `docs/v2-plan.md`, evidence in
`docs/strategy-research.md`. From M2 on, work runs under a standing approval (see `CLAUDE.md`); every decision made without
asking is logged here.

### M0: toolchain and research (a4b80ee)

- Room 2.8.5 + KSP 2.3.12, WorkManager 2.12.0, Navigation Compose 2.10.2, kotlinx.serialization 1.11.0, Material 3 adaptive
  (from the Compose BOM). KSP runs under AGP 9's built-in Kotlin; no fallback needed.
- Engine test helpers and golden data moved to the `testFixtures` source set.

### M1: engine groundwork

- Deleting the five Signal Lab 1 engine files here would have broken the 1.7 app code still on the branch, so they stay until
  M6 and carry a header saying so; `LayeringTest` keeps new `strategy/` and `eval/` code off them.
- Golden paper-trade, fingerprint and exit tests read the research's saved signals (`Golden.researchSignals`) or synthetic
  signals instead of `Signals.kt`.
- `RandomFingerprintTest`: the saved signals arrive in name order, which moved the last digit of one sum
  (18.34023686426625 → 18.340236864266156); its three sums are now compared to 1e-12 relative, counts still exact.

### M2: strategy interface, first two built-ins, fills, baseline, backtest

- New `engine.strategy` (`Strategy`, `Tier`, `ExitPlan`, `Breakout`, `TrendTimer`, `Builtins`) and `engine.eval` (`Fills`,
  `Baseline`, `Backtest`). They use only the kept core (candles, indicators, regime, RNG, cluster statistics); the old
  `ExitRule`/`ExitPolicy` are not used because they depend on the old lab language.
- Decisions made without asking:
  - A strategy's own exit signal, seen at a candle's close, fills at the **next candle's open** (the first price anyone could
    act on), matching entry at the next open. Time exits still fill at the close of the last candle; the stop is checked first.
  - Breakout enters on the first close of a run above the prior 50-day high (not every day it stays above). Exit plan: close
    below the prior 20-day low, 60 days at most, safety stop 3 ATR.
  - Trend timer has no price stop (its edge is drawdown avoidance); a 200-day cap keeps every window finite for the baseline.
  - Weeks for clustering start Monday 00:00 UTC (v1 used `time / 7 days`, which starts on Thursdays).
  - v2 baseline seeds are prefixed with "v2" so they never coincide with v1 draws.
  - Only the engine changed, so the app suite was not rerun for this milestone; it runs again at M3.
- First backtest on the 12 golden coins, daily, 2019-01 to 2026-09, research costs:

  | strategy | calls | up | net per call | excess vs random | cluster t | weeks |
  |---|---|---|---|---|---|---|
  | breakout50_1d | 274 | 39% | +19.50% | −3.05% | −0.66 | 132 |
  | trend50_1d | 912 | 22% | +12.95% | +5.58% | 1.34 | 254 |

  Neither beats random entries with confidence here: random entries with the same wide exits also made money in a mostly
  rising market. Launch tiers stay as the research suggests; the evidence columns (M7) will move them.

### M3: data port

- Each candle keeps its USDT turnover (kline field 7); `candles.db` goes to version 4 with an `ALTER TABLE` that keeps every
  stored row (old rows count as volume × close). `CandleStore.quoteVolumeSince` and `firstOpens` feed the universe.
- Daily candles are never trimmed (`DataConfig.ALWAYS_KEPT`), and `DailyHistory` fetches them back to the listing
  (from 14 Jul 2017; the days before a listing are remembered as a known gap and asked once).
- `Universe.select`: the 40 busiest USDT coins by 30-day turnover that pass the coin filter and were listed at least 90 days
  ago, plus pins (which override ranking and age, never delisting), minus excludes. A coin whose age is unknown waits.
- Futures: `FuturesClient` (funding history, open-interest history, premium index) goes through the spot client's pacing and
  retries via `BinanceClient.fetchFrom`; a symbol with no perpetual is no data, not an error. `DerivativesStore`
  (`derivatives.db`) and `DerivativesSync` (funding back to Sep 2019; open interest forward from what is stored, never older
  than 29 days, so it accumulates on the phone). Nothing is asked on Binance.US.
- Decision: the liquidation veto is deferred. Binance has no key-free REST history of liquidations, and its stream reports
  at most one per symbol per second, so totals undercount in exactly the cascades that matter.
- Engine 172, app 752 tests pass.

### M4 + M5: ledger, importer, live runner, daily scan

- `ledger.db` (Room, schema in `app/schemas/`): calls, exits, the strategy registry, custom strategies and their stops,
  reports, pins, settings, Signal Lab 1 list names, and the import log. `calls`, `exits`, `strategies`, `custom_strategies`,
  `custom_stops` and `import_log` refuse UPDATE and DELETE by trigger; the triggers are created on create and on every open.
- `LegacyImporter` reads `signal_lab.db` through `ledger/legacy/SignalLabV6` (a verbatim copy of Signal Lab 1's six schema
  steps, so it outlives Signal Lab 1's code) in one transaction, keyed so repeats add nothing. Decisions:
  - Signal Lab 1 keeps writing to its file until M6, so the importer runs at every start and adds only what is new; a trade's
    "unresolved" state is the absence of an exit row (a status written once could not change), and only the first import
    keeps a copy of the file, so copies never pile up and nothing ever needs deleting.
  - Pins come from the coins of *active* lists only; alerts are not imported (counted in the log); settings keep fees, host,
    screen, notifications, sync and asked-permission keys, with `scan_in_background` renamed `background_enabled`.
- Engine `eval.Live`: `signal` (a call on the newest closed candle, entering at the forming candle's open, regime gate, ATR
  sizing: 1 ATR = 1% of the paper account, at most 20%) and `close` (the shared fill rules; an exit that fills at the forming
  candle's open, a signal exit or a gap through the stop, is reported at once instead of a day later). `Fills.exitTime` makes
  the backtest and the live runner time a fill at an open the same way. `LiveEqualsBacktestTest` replays three golden coins
  day by day and requires every live call to equal its backtest trade, both ways.
- `StrategyRunner` (app): refresh the pair list, bring every offered coin's daily history up to date (the universe and the
  coming cross-coin strategies need them all; about one request per coin per day), choose the universe, close open calls,
  open new ones, notify. Busy rule as the backtest's: a coin is busy while a call of that strategy is open or its exit is at
  the very open a new call would enter at. Calls beyond 8 open are recorded with size 0 (tracked, not in the account), so the
  evidence keeps every signal. Only Proven and Promising strategies notify.
- Scheduling: an exact alarm 90 s after 00:00 UTC (Binance's clock) starts a unique one-time job; a 6-hour periodic job is the
  backstop; boot and app updates re-arm both. Decision: not "expedited" work, which on Android 10 and 11 must run as a
  foreground service; the exact alarm already gives the timing.
- Decision: Signal Lab 1's scanner and screens keep running beside the runner until M6 (the plan had the old service removed
  at M5). That keeps the phone usable at the M5 install and lets the v2 strategies collect live calls from day one.
- Known optimism, as in Signal Lab 1: a call enters at the forming candle's open even if the pass runs later in that candle;
  the exact alarm makes that seconds in practice.
- Test stabilisation in Signal Lab 1 code (removed in M6): Robolectric starts the real application, whose background start now
  also imports and arms the v2 alarm. `PatternLabels.labTitles` gets a synchronized setter so a background merge cannot write
  over a value set at the same moment; the long day-by-day replay in the old `LiveEqualsBacktestTest` gets a 5-minute limit;
  `AndroidAlarmsTest` counts only the old scanner's own alarm. A run with leftover Gradle test JVMs failed with Robolectric's
  native SQLite not loading; a clean run (`./gradlew --stop`) does not.
- R8 keeps Room's generated `LedgerDatabase_Impl` and `LedgerDao_Impl` (only unused members are stripped). Release APK 5.9 MB.
- Engine 174, app 763 tests pass; release build signed from `local.properties`.

### M6: the new screens, and Signal Lab 1 removed

- New look "Night desk" (the approved mockup, artifact RSHKrn1juGBc16HRA1wM9m): `view/theme`, shared parts in `view/common`,
  icons drawn from the mockup's own stroke paths (no icon library).
- Screens: Today (market line, new calls, open calls with what to do, leave-now rows, this week's exits), a call's page
  (chart with entry, safety stop and exit lines; the plan; why now), Ledger (account curve against random entries, period
  switch, by strategy and coin, Signal Lab 1 history, CSV), Strategies (tiers, on/off), a strategy's evidence (research,
  backtest on your coins at your fees and at double, live record, sources, exact rule), Markets (market cards, search,
  watched coins), a coin's page (chart, its calls, pin or exclude), Settings, and an Analyst placeholder until M8.
- Navigation Compose with typed routes; a bottom bar under 600 dp, a side rail from 600 dp, and from 700 dp Today puts the
  chosen call's page beside the list (the Fold7's inner screen).
- State in `model/` over the ledger's Room flows; preferences (`ledger/Preferences`) for account size, costs, switches,
  notifications, host and futures data. The runner reads them: costs, and whether a strategy notifies (switched by hand, else
  Proven or Promising). Every call is recorded either way.
- Decisions made without asking:
  - Fonts: the phone's own sans serif and monospace with tabular figures. The mockup's IBM Plex would need font files
    downloaded; left for the owner to decide.
  - Calls from strategies that are off are hidden from Today but still recorded and still in the Ledger, marked "Off Today".
  - A strategy's live line says "About K more calls before a live verdict" with K counted to 100 calls; M7 replaces this
    with the minimum track record length.
  - On first start after the update: Signal Lab 1's alarm is cancelled and its five notification channels removed (once),
    and a pass runs at once if none ran in the last day. The notification permission is asked once on Android 13+.
- Removed: Signal Lab 1's `ui/`, `state/`, `scan/`, `analyst/` and `sync/` packages, its record classes, the Learn pages and
  Mermaid, the foreground scan service and its permissions; in the engine `Signals`, `Candlesticks`, `LabPatterns`,
  `LiveScan`, `VariantLabels`, the learned exit and end-of-day rules. Drive sync returns in M9 on the new ledger.
- Tests: the old screen, state, scanner, sync and lab tests go with their code; golden, fingerprint and exit tests keep their
  numbers exactly (the classic scorecard path is unchanged). New `ScreensTest` and `ModelsTest`.

### M7: five more strategies, vetoes, evidence, tiers, and the rule language

- New built-ins (docs/strategy-research.md): Momentum 2w, Leaders (weekly ranking, needs at least 8 coins), Near high,
  Quiet dip and Hourly trend, beside Breakout and Trend timer: seven in all. Each has its plain words, sources and exact rule
  in `model/StrategyTexts`.
- Vetoes on every entry, backtest and live alike: signal-day volume over 5× its 30-day median together with a range over
  3 ATR. A vetoed signal is not recorded as a call. The liquidation-spike veto waits for enough stored futures data.
- `eval/Evidence`: per-call Sharpe moments, probabilistic and deflated Sharpe, minimum track record length, a stationary block
  bootstrap and the probability of backtest overfitting (combinatorial cross-validation). `eval/Tiers` turns the backtest at
  the person's fees and at double, the live record and the number of strategies ever tried into a tier and a sentence.
- The runner now wakes 90 seconds after every hourly close. The daily pass runs once per daily close (setting
  `daily_pass_day`); the hourly pass runs Hourly trend on the universe's hourly candles (100 days kept). Tiers are
  recomputed after each daily pass and stored as `tier.<id>` and `tier_reason.<id>`; Today, Strategies and notifications
  read them. A strategy switched by hand keeps its switch.
- The live line on Ledger and the evidence page now counts the calls still needed with the minimum track record length,
  never fewer than 100 in all.
- The rule language for custom strategies (`engine/strategy/dsl/Rule.kt`): a bracketed text form the engine parses, checks
  and prints back exactly, with 30 operations that can only read the candle they are on and earlier ones. Limits: windows and
  trades up to 500 candles, 120 operations, 12 levels, timeframes 1h, 4h and 1d only. Decided without asking: a text form
  instead of JSON, because the engine has no JSON library and the analyst can write it as one string; an entry condition that
  holds for several candles enters once. The builder screen and the analyst's use of it come in M8.
- Decisions made without asking:
  - The backtest ignores live universe ranks: it runs on whichever coins it is given.
  - The hourly strategy is only judged once hourly candles exist.
- Finding: on the 12 golden coins only Trend timer reaches a tier that makes calls (Promising). Breakout and Momentum are
  Watch, Leaders is Failing, Near high, Quiet dip and Hourly trend stay Testing. Today will show few calls until the owner
  switches strategies on or live calls build a record.
- Engine 123 and app 172 tests pass (2 skipped); release APK 4.2 MB.

### M8: the analyst

- Claude through Anthropic's official Java SDK (`com.anthropic:anthropic-java` 2.34.0, OkHttp client), on the owner's own
  API key. Default model Claude Opus 5.5, Claude Sonnet 5.5 selectable; thinking is always on for Opus 5.5, so no thinking
  setting is sent. Each request streams; the system prompt (with the rule language reference) is cached.
- Decided without asking: a hand-written loop rather than the SDK's beta tool runner, because the screen needs text as it
  streams, a search the server pauses (`pause_turn`) must be resumed, and the monthly budget is checked before every
  request. Client tools use eager input streaming; the phone checks every tool input against its schema before running
  it and answers a bad one with an error result. At most 12 requests and 4 resumes per run.
- Tools, run on the phone: `market_state`, `ledger`, `list_strategies`, `strategy_evidence`, `candles`, `derivatives`,
  `backtest`, `propose_strategy`, `suggest_call`, `flag_context`; plus Anthropic's `web_search` (5 per request) and
  `web_fetch` (3). The analyst cannot trade, change settings or record a call.
- Every rule the analyst backtests is registered in the strategies table under an id made from its canonical text, so it
  counts once toward the number of strategies tried. A proposal is saved as a custom strategy with a report; it runs only
  after the owner starts it in Strategies (`custom_live.<id>`), in Testing, forward-only.
- `make_call` became `suggest_call`: a suggestion waits on Today (at most 3, 3 days); a recorded one enters at the next daily
  open after it was recorded, as a call of the strategy "Analyst", with its own stop, target and time limit. The engine's
  exit plan gained an optional target (filled at the target or at a gap above it; the stop is still assumed first), and the
  analyst's random entries use the same stop and target in ATRs.
- The key: Android Keystore AES-GCM, in the no-backup folder; never synced, logged or sent anywhere but Anthropic.
- Spend: a monthly meter (`analyst_spend.<yyyy-MM>`) priced from each response's usage (Opus 5.5 $4/$20 per million tokens,
  Sonnet 5.5 $2/$10, cache reads $0.20, searches $0.01); default budget $20 a month.
- Decided without asking: the analyst's key, model, budget, brief and review live on the Analyst tab; Settings points there.
  The conversation keeps text only (the last 16 messages go with a question); the last 50 runs are kept for the "What was
  sent" page. The daily brief and weekly review are off by default and run as WorkManager jobs after a pass, with a
  network.
- Without a key, the Analyst tab shares a question with a data snapshot to the Claude app (read only).
- Fixed: the rule language read "1d" as the number 1 (Java's number parser accepts a d suffix), so `(timeframe 1d)` failed
  after a round trip. Numbers are now read with a strict pattern.
- Release: R8 keeps the SDK's message models and Jackson's binding whole; the APK grows from 4.2 MB to 13.4 MB.
- Not verified yet: a real run against the Claude API (it needs the owner's key) and the shrunk SDK on the phone.

### M9: sync, release preparation

- Sync between devices returns on the new ledger, through the same hidden Drive folder, Google authorization and OAuth
  client as Signal Lab 1 (`sync/`: `DriveFolder` and `DeviceSync` ported, `LedgerSync` and `SyncEngine` new). Each device
  writes only its own file; Signal Lab 2 writes `device-<id>.v2.json.gz` in format 2 and reads every device's file, Signal
  Lab 1's format 1 included. Signal Lab 1 sees format 2 as a record it cannot read and leaves it alone (it says a device
  needs the newest version). The device id carries over from Signal Lab 1; a device that had sync on keeps it on.
- Format 2 carries the strategies registry, every call with its exit, custom strategies with their stops, reports, pins and
  the settings that follow the owner. Calls match by strategy, coin, signal candle and scope, so two devices that make the
  same call keep one. A Signal Lab 1 lab pattern's call travels under its sync id and comes back under this phone's id.
- Format 1 is read the way the importer reads Signal Lab 1's own file, so a trade already imported here is not added twice.
- Decided without asking:
  - Synced settings: account size, costs, data source, futures data, strategy switches, custom strategies running, the
    analyst's model and budget, its suggestions and context lines. Not synced: notifications, the brief and review
    switches (so only one device pays for them), tiers (each device computes them), the spending meter, sync's own state.
  - Pins are merged as a union: removing a pin is not synced. Signal Lab 1's settings in a format 1 record are ignored.
  - Rounds run on start, on returning to the app, after each pass and shortly after anything worth sending changes, at
    least five minutes apart; the setting changes that trigger a round are only the synced ones.
- The updater needs no change: it already ignores drafts and pre-releases. The release workflow now publishes a
  pre-release when the version's `CHANGELOG.md` heading ends in "(pre-release)", and 2.0.0 is marked so: Signal Lab 1.7's
  updater offers only the latest full release, so no phone is offered 2.0.0 before it is verified on the Fold7.
- `CHANGELOG.md` has the 2.0.0 section. The `v2.0.0` tag is not made: it waits for the owner (stop point 3).

### M10: the analyst on the owner's PC

- Approved with "approve" on 2026-10-17, after the owner said their plan has no API credit. The owner's consumer login is
  never used by Signal Lab: the PC runs Anthropic's own Claude Code, signed in as it already is, and Signal Lab only talks to it.
- `analyst-host/`: a Kotlin program on the JDK alone (its HTTP and HTTPS servers are the JDK's). For each question it starts
  `claude -p` with stream-json output, the analyst's instructions as the system prompt, `--restricted` (no settings files, so
  none of the owner's hooks, plugins or global instructions), `--tools WebSearch,WebFetch`, the bridge as the only MCP server
  (`--strict-mcp-config`), `--permission-mode dontAsk` with only those tools allowed, `--max-budget-usd 5` as a runaway stop,
  and no saved session. The conversation so far goes in the input as a transcript.
- Decided without asking:
  - Claude Code's command line, not the Agent SDK: there is no Agent SDK for the JVM, and the CLI is what is installed.
  - `--safe-mode` is not used: a live run showed it also drops the bridge's MCP server. `--bare` is not used: it reads only
    an API key, never the plan's login. A live check showed restricted mode keeps the owner's global CLAUDE.md out.
  - The tools stay on the phone: the bridge (MCP over HTTP on 127.0.0.1, with its own random token) forwards each call
    to the phone through the run's event stream and waits up to 60 seconds for the answer. The PC keeps no copy of the
    ledger or candles, so there is nothing to sync and no JDBC port of the candle store.
  - Arguments reach Claude Code on Windows' command line, where Java does not escape quotes: the MCP configuration goes
    in a file, and straight quotes in the instructions become typographic ones.
  - One question at a time (409 otherwise). The phone's Stop ends the run at once (`POST /runs/<id>/stop`); a dropped
    connection is noticed at the next keep-alive, within five seconds, and Claude Code is killed.
  - Pairing: the host prints its LAN address, a 10-character code and the end of its certificate's SHA-256; the phone shows
    the fingerprint it received and pins it once the owner confirms the two match. The code is sealed like the API key; the
    address and pin are settings that are never synced. A wrong code waits longer each time.
  - Runs on the PC cost nothing on the API budget; the run's log notes the plan's limits used (from Claude Code's rate-limit
    event) and what it would have cost at API prices.
  - The brief and the review, with the PC off and no key, are queued again after the next pass instead of failing.
- Tests: host 8 against a stand-in Claude Code (recorded real output, real bridge calls), plus a live test run once through
  real Claude Code on the owner's login (the tool was called and its answer used); phone 5 for the PC route, fallback and
  pinning with real certificates, 3 screen tests. Engine 128, host 9, app 213 pass; release APK 13.5 MB.
- Not verified yet: a run from the phone itself to the PC over the home network.
- Verified on the Fold7 2026-10-17: paired with the host on the owner's PC (fingerprint checked by the owner) and answered
  a question through it in 18 seconds, with the app's tools. Fixes found on the way:
  - The JDK's HTTPS server looks up the name of every client; on Windows a phone's address has none, and the lookup took
    five seconds per connection, so the phone's four-second code check failed and showed "refused the pairing code". The
    host now points the JDK at an empty hosts file, so lookups answer at once.
  - The start script calls Gradle's wrapper and the host by full path (this shell does not run programs from the current
    folder by bare name), and `.cmd` files keep CRLF line endings.
  - The host's watcher thread no longer prints an InterruptedException when a run ends normally.
  - Port 8765 was held by another program on this PC, so the host ran on 8766 (`--port 8766`).
- Open: when the phone's code check fails for any reason it says the code was refused; it should say why. And the
  analyst noticed that Trend timer's tier reason says "not yet at double the fees" while its evidence shows +4.7% per call
  at double fees: the rule likely needs confidence, not just a positive mean, and its words should say so.

### 2026-10-17: two open items from the host pairing, fixed (approved)

- Pairing check says why: `HostClient.verify()` does the `/check` call and throws a sentence for each failure (code refused,
  no answer in four seconds, a different PC's certificate, another status, not reachable). `check()` is now
  `runCatching { verify() }.isSuccess`, and `HostPairing.probe` calls `verify()`, so "refused the pairing code" appears only for a 401.
- Tier reason: the Promising sentence no longer says "not yet at double the fees" whenever Proven is missed. It says "not ahead
  at double the fees" when the mean at double cost is not positive, and "still ahead at double the fees, but not yet confident
  enough after allowing for the N strategies tried" when it is positive but the deflated Sharpe is not above 0.95. The tier rules
  and thresholds are unchanged: Trend timer's +4.7% at double fees is a positive mean that does not clear the deflated Sharpe bar.
- Tests: `TiersTest` for both sentences; `AnalystPcTest` for a 500 and a slow answer during pairing.

### 2026-10-17: CI failure on the first push of v2, fixed (approved)

- `AnalystPcTest.pairingPinsThePcsCertificateAndNoOther` failed with a `BindException` on GitHub's runner in both the `main` CI
  run and the `v2.1.0` release run (213 tests, 1 failed), and passed locally. The test restarts a stand-in PC with another
  certificate on the same port. First guess (wrong): the runner had not released the port yet, so the rebind got a retry for
  five seconds; CI failed again the same way. `app` unit tests now print full failure traces, and the trace said
  `java.net.BindException: Address already in use` from `MockWebServer.start(port)`, still after 50 tries.
- Cause (inferred from the trace, not run on Linux here): the first stand-in started on port 0, where MockWebServer does not set
  SO_REUSEADDR, and on Linux a port its sockets (including closing and TIME_WAIT ones) held cannot be bound again for a minute. Windows
  allows it, so the test passed locally. The test now starts the first stand-in on a named free port, which makes MockWebServer set
  SO_REUSEADDR, and the retry is gone. No 2.1.0 release had been published.

## Signal Lab 3 (branch `v3`)

Plan in `docs/v3-plan.md`, approved by the owner on 2026-10-22 with the literal "approve" on its fifteen-item list. The owner
decided the same day that the app keeps paper money and never places orders: calls are copied on Binance by hand. Research in
`docs/research-2026-10/` and `docs/strategy-research.md` §7.

### Milestone 1 (2.2.0): data, settings, market line

- New keyless feeds, all in `derivatives.db` (version 2, tables `basis`, `positioning`, `dvol`, `notices`; a version 1 file
  gains them on upgrade): Binance `/futures/data/basis` (perpetual and current quarter) and the three positioning ratios for
  BTC and ETH, accumulated from Binance's 30 days; Deribit DVOL daily for BTC and ETH back to 24 March 2021; Binance CMS
  delisting catalog 161 and Upbit trade notices; funding for every watched coin, not only BTC and ETH.
- Decided without asking:
  - Every feed runs once per daily pass, each in its own `runCatching`: a refused feed never stops the pass. A notices source
    that fails leaves the other one read.
  - Binance's website (`www.binance.com`, which serves the CMS) refused connections from the owner's PC on 2026-10-22 while
    its API answered. Not tested from the phone yet; if it fails there, Upbit notices still arrive and a Binance delisting is
    still caught at the halt by the symbol's status.
  - Only spot delistings are stored from Binance: headlines matching "Binance Will Delist X, Y on DATE", skipping futures,
    margin, Alpha, Earn and pair-removal notices. Upbit: "거래지원 종료" is a delisting, "거래 유의" a caution (not its lifting).
  - The app has no Android backup (`allowBackup="false"`), so "backup and sync" for the new tables means device sync. Only
    basis and positioning travel (as `market` in the format-2 record; older versions ignore the key): funding, DVOL and
    notices can be downloaded again. Open interest, already stored since 2.0, still does not travel; left as it was.
  - The DVOL row of the newest day is replaced on the next run, because Deribit's daily candle changes until the day closes.
  - Market line: `FuturesContext` reads 30-day and 7-day mean BTC funding (annualised at three settlements a day), the
    current quarter's annualised premium and DVOL with its 7-day change. "Overheated" is 7-day funding above 20% a year with
    the quarterly premium above 15%, or DVOL up more than 15 points in a week (the plan's numbers). The analyst's
    `market_state` tool returns the same figures.
- `Strategy.retired`: a retired strategy opens no new calls; its open calls still close by its rules and it stays in the
  registry. Hourly trend is retired (121 live calls, mean excess −0.76%, t = −2.16). The hourly pass now follows only coins
  with an open hourly call, so it stops downloading hourly candles for the whole universe once those close. Retired
  strategies are left out of the market line's "N strategies may fire".
- `UNIVERSE_MIN_AGE_DAYS` 90 → 180.
- Tests: feed parsing and accumulation (basis, positioning, DVOL paging and refresh, notices with one source failing, Binance
  headline parsing, version 1 → 2 upgrade), the futures sentence and its numbers from stored data, a retired strategy in a
  replay, the 180-day rule, and the futures series through sync.

### Milestone 2 (2.3.0): sizing engine, vetoes, what to buy

- `engine/eval/Risk.kt`: a new call starts at its plain ATR size, then (1) trend strategies (Breakout, Trend timer, Momentum,
  Leaders, Near high: `Strategy.trend`) are scaled by the close's distance above its 50-day average in ATRs, 0.5 at the
  average, 1 at two ATRs, 1.5 at six or more; (2) heat halves or blocks; (3) the book is held at 25% a year of volatility by a
  one-factor model (Bitcoin): beta over 90 days, own variance over 20; the new call shrinks until the book with it is at the
  target, or to nothing; (4) beta-weighted exposure stays under 150% of the account; (5) with 100 or more finished sized live
  calls of strategies still running and a positive mean, total exposure stays under half the Kelly fraction. A losing record
  sets no Kelly cap: stopping a losing strategy is the tiers' job, and a cap of zero would hide every call.
- Decided without asking:
  - Only daily calls go through the engine; an hourly call keeps its plain size (no hourly strategy runs now).
  - Heat: BLOCK when 7-day BTC funding is above 20% a year and the quarterly premium above 15% (the plan's "both hot"); HALF
    when DVOL rose more than 15 points in a week.
  - A delisting notice counts for 30 days after it was published. It blocks new calls on the coin (veto recorded, size 0)
    and closes open daily calls on it at the next open with reason `notice`. Their random entries are held as long as the
    call was and keep its safety stop. Upbit "caution" notices are stored but do not block: the research found them
    reasonable but unmeasured.
  - Vetoed calls are recorded with size 0 and `veto` set, and are not notified.
  - The daily pass now closes every exit before opening any call. Before, a call leaving at this open still counted in the
    book while coins earlier in the list were sized (found by the runner test: Bitcoin's call was sized at zero because
    yesterday's ETH and SOL calls, leaving at the same open, were still counted).
  - The futures feeds and notices are now synced before the calls of the pass, not after, so the calls of a close are sized
    and vetoed by the context of that close.
  - "Money I trade with" (`trade_money`, synced between devices) sizes the buy line: the call's share times the money, rounded
    down to a whole USDT, then to the coin's LOT_SIZE step at the entry price. Each pair's step and minimum order are read from
    `exchangeInfo` with the pair list into a new `lot_rules` table (candle store version 5).
- Ledger (Room version 2, auto-migration): `calls.plain_size` and `calls.veto`, also in device sync. A new test builds a
  version 1 file from Room's exported schema and checks that its calls survive and the record stays append-only.
- Tests: engine `RiskTest` (7); runner tests for a delisting notice and for overheated leverage; buy-line wording; order rules
  from `exchangeInfo`; the ledger migration.

### Milestone 3 (2.4.0): Hated rally, Trend leaders, copies

- **Hated rally** (`hatedrally_1d`, Testing, ranks 1 to 12): enters when the 30-day mean funding turns negative after a positive
  30 days before it, or the 7-day mean is below −5% a year (annualised at three settlements a day); `firstOfRun`, so once per
  stretch. Exit: close below the prior 20-day low, 60 days, stop 3 ATR. Funding is not candles, so it reads a `FundingBook`
  the runner fills from `derivatives.db` before every daily pass (and once at app start). It reads only settlements at or
  before each candle's close, which keeps the no-look-ahead rule; a test cuts history and checks it.
- **Trend leaders** (`trendleaders_1d`, Watch, weekly on Monday, 7-day hold): the 28 CTREND indicators exactly as the paper
  defines them (§III.B: RSI, stochRSI, stochK, stochD, CCI; SMAs 3 to 200 over close; MACD and its signal gap; dollar-volume
  SMAs 3 to 200 over today's dollar volume; volume MACD and its gap; Chaikin money flow; Bollinger low, mid, high over close
  and width). Combination as the paper's combined Fama–MacBeth: per indicator, weekly cross-sectional regressions of next-week
  returns (less the week's average) on the indicator; each forecast uses the average intercept and slope of every past week
  whose return is known (at least 26).
- Decided without asking:
  - The paper selects forecasts with an elastic net (α 0.5, λ by corrected AIC) on the last 12 weeks, then averages those with
    a positive weight. Here a forecast is kept when it moved with the coins' returns over those 12 weeks (the paper's own
    θ > 0 restriction without the shrinkage); if none qualifies, all are averaged. Simpler, no tuning, documented in the class.
  - The paper ranks the 100 largest coins; the app's universe is the 40 busiest, so the top fifth is 8 coins.
  - A coin still in the top fifth next Monday gets a new call rather than an extended one, so each week pays its fees: the
    paper's weekly rebalancing pays turnover the same way.
  - `Indicators.sma` uses a running total, so a series with leading gaps (stochK) would be gaps forever; stochD is computed
    directly. Found when Trend leaders did not fire on the golden coins.
  - The golden test candles have no funding, so the golden fixture installs a synthetic funding history (a slow wave with
    noise) for its daily panel. Hated rally's line in the printed backtest table therefore means nothing.
  - On the twelve golden coins: Trend leaders 1,021 calls, +1.49% net, −0.59% behind random entries per call (t = −1.01).
- **Copies**: a `copies` table (Room version 3, auto-migration), not append-only because the owner may correct a price; the
  newer `updated_at` wins between devices, and copies travel in sync keyed by the call's natural key. "I did not take it"
  deletes the copy on this device; a device that synced it earlier may bring it back, since there is no tombstone (rare,
  and the owner can remove it again). A copy's result uses the call's assumed round-trip cost. "My copies" in the Ledger
  shows the average real result beside the paper result on the same calls and the average gap between the owner's buys and
  the paper entries.
- Notifications: new calls add "Buy N USDT (X% of your money)" when "Money I trade with" is set; exits are titled "Sell now".
- Tests: engine 140, app 233, all passing.

## Signal Lab 4 (branch `v4`)

Proposed and approved by the owner on 2026-11-14 ("approve"), after a whole-account test of every daily strategy on
2020 to 2026 history. Five parts; none installed, none pushed. Findings: `docs/strategy-research.md` §8.

### 2026-11-14: the five parts

- **Part 1, the test kept.** New module `research/` (JVM, depends on `:engine`, not part of the app) and
  `tools/download_history.py`; history goes to `research/data/`, which is gitignored (about 60 MB). The account simulation is
  `engine/eval/Account.kt`, so the tool and any later screen share one implementation.
- **Part 3, never more than 100%.** `Risk.fit` cuts a new call to the room left; under 1% of the account it is skipped
  (tracked with no size) instead of shrunk to an order Binance would refuse. The runner keeps the held share in its tally.
- **Part 2, the whole account in the app.** Decided without asking:
  - The phone cannot run the test (it has no history of delisted coins, and today's 40 coins alone would flatter every
    strategy), so the results are constants in `AccountRecords`, copied from the tool's output. Rerun and replace them when a
    strategy changes or a year passes.
  - The tier rule sits after both Failing rules and after Proven, and needs the research to support the strategy. Trend
    timer is the only built-in it lifts. Breakout and Momentum now depend on the phone's own backtest alone, as before.
  - Quiet dip and Trend leaders are `retired`, like Hourly trend: open calls close by their rules, records stay.
- **Part 5, the stop order.** A call's plan has a row "Stop order on Binance": a Stop-Limit sell at the safety stop with the
  limit 0.5% under it. Prices are shown with the app's usual rounding, not Binance's tick size, which the app does not store.
  Trend timer has no safety stop, so it has no such row; its exit is a daily close, which the morning plan carries.
- **Part 4, the morning plan.** Off by default (Settings, Copying calls on Binance). When on, single alerts are held and the
  first hourly pass at or after the chosen hour on the phone's clock posts one notification: sells, then buys with amounts
  and stops. No new alarm: the pass already runs every hour. A day with nothing to do posts nothing. Tapping it opens the app.
  Its state (`plan_day`, `plan_at`) is per device and not synced.
- **"Copy amount and open Binance"** on an open call's page: the amount goes to the clipboard and
  `https://www.binance.com/en/trade/<COIN>_USDT?type=spot` is handed to the Binance app (`com.binance.dev`) if it is
  installed, otherwise to the browser. **Not checked on a phone**: whether Binance's app opens that link on the trade
  screen has to be seen on the Fold7.
- Found, for the owner to decide: calls of strategies that are *not* on Today took room in the one paper account, so they
  shrank the amounts of the calls the owner does copy.

### 2026-11-14: only a strategy that is on holds part of the account (approved)

- In `StrategyRunner.openCall`, a call of a strategy that is off (by its tier or by hand) is recorded with size 0 and its
  plain size kept, with no veto: its result against random entries still feeds its tier, so it can still earn its place.
  The paper account is now the account of what Today shows, which is what the owner copies.
- Decided without asking: a call the owner recorded from the analyst keeps its room whatever the analyst's tier, since
  recording it was the owner's choice. Open calls keep the size they were given; switching a strategy on or off changes
  only later calls. The wording for a call with no size now reads "the account was full, or its strategy was not on".
- Tests: engine 147, app 238, all passing.

### 2026-11-14: the Binance link, and release 2.5.0 (approved)

- Checked on the Fold7: the Binance app (`com.binance.dev`) declares no handler for www.binance.com, so the button fell back to
  the browser; `https://app.binance.com/en/trade/...` opened the app on "This link does not work". The app's own
  `bnc://app.binance.com/trade/trade?at=spot&symbol=<coin>usdt` opened the coin's Spot trade screen. The browser fallback
  keeps the www.binance.com page. A line under the button says to paste the amount into Total (USDT).
- Released as 2.5.0 (final, per the owner's no-pre-release rule), `v4` fast-forwarded into `main`.

## Signal Lab EGX (branch `egx`)

Built from `docs/egx-build-list.md` under one "approve" given 2026-11-14 for items 1 to 46. Every decision made without
asking is logged here.

### E1: research and engine

- Item 1: the 15 listed scripts copied unchanged into `tools/egx/` with a README. The research data (raw Yahoo JSON,
  hourly bars, prepared candles, about 330 MB) and every other script of the research folder copied to
  `research/data/egx/`, which is already gitignored, so a temp-folder cleanup cannot lose them.
- The plan, the build list and the scripts were committed first, before any engine change, so the side sessions' worktrees
  start with them.
- Item 3: `engine/.../Market.kt` holds each market's settings (sessions a year, round trip by rank, cash rate, dividend
  tax, price limit, locked move, price step). `Account.summarize` takes the market's sessions a year (crypto keeps 365).
  The EGX price step is taken as 0.01 pounds for every price: an assumption to check against Thndr.
- Items 4 to 9: the Egyptian rules hold stocks in slots, so they live in a new package `engine/.../egx/` (`Board`,
  `Dividends`, `Slots`, `Rules`, `Yardsticks`) instead of the crypto `Strategy` interface. `Slots.run` is a line-by-line
  port of the research's `run_levels`.
- Month ends: the evening pass cannot see tomorrow's candle, so a month ends when the next session day (weekdays less
  the exchange's published holidays) is in another month. Guessing from weekdays alone put 24 of 201 month ends a session
  late and moved results up to 50%; with a holiday list the engine matches the research. The research takes the holidays
  from the weekdays the market did not open; the app will keep a table the owner can correct.
- Quarterly re-picks (Low-risk formula, holding the 30 busiest) follow the research's test: when the next session opens a
  new quarter. This matters only around long closures (27 Jan to 23 Mar 2011).
- Item 5: a buy blocked by a lock or a session without trades waits up to 5 sessions, until the rule decides again (the
  research dropped it). This leaves 2023-26 unchanged and moves 2015-22 by up to 16% (walk-forward picks), under 3% for
  fixed settings. `buysWait = false` reproduces the research.
- Item 8: the entry range fills at the last close or below, as tested; its lower edge (half a move below) is shown only.
- Item 10: random twins buy on the rule's own buying days from its own pool and sell alongside it, 20 counter-based draws.
  Tier rule: 12 months live before any change; a rule on Today drops to Watch behind cash and the EGX30, or below the
  random tenth percentile; Watch rises after 24 months ahead of all three and the random ninetieth percentile.
- Items 11 and 12: `tools/egx/export.py` writes dividends.csv and cash.csv; `gradlew :research:run --args="egx"` re-makes
  the plan's table. Every comparable line matches the research within 0.5% (output in `research/data/egx/out_engine.txt`;
  with waiting buys in `out_engine_wait.txt`).
- Item 13: 26 engine tests (`engine/src/test/.../egx/`) with a synthetic board in `testFixtures/EgxSynth.kt`; every
  registered rule gives the same decisions on history cut at, or scrambled after, four points on three seeds.
- Item 2: `docs/egx-research.md`.

### E2: Egyptian data (session B)

- All code is in `app/.../data/egx/` (new files only); no Gradle or engine change. Plain `SQLiteOpenHelper` store in its own `egx.db` (like `CandleStore`), not Room, so no schema file lands outside the package.
- Yahoo: daily by `CODE.CA` (`period1/period2`, `interval=1d`, `events=div,splits`), hourly by the ISIN symbol (`range`, `interval=1h`), browser User-Agent as the research used, 0.4 s between calls, 4 attempts, 404 = "no feed".
- Session day = date of (timestamp + Yahoo's gmtoffset), as epoch ms of UTC midnight (the research's key). A session counts as closed from 15:00 Cairo time (java.time `Africa/Cairo`, so summer time is handled); open sessions are never stored.
- Stored daily rows keep Yahoo's raw high/low/close/volume, never its open. The 21% flattening is applied when a series is built, so a new jump never rewrites the store. Turnover = raw close × volume, as in `prepare.py`. Friday/Saturday rows are dropped.
- Real open = open of the session's first hourly bar that shows trading (volume > 0 OR high != low). Deviation from `fills.py`, which skipped zero-volume bars: Yahoo reports the 10:00 bar with zero volume every day though it has a range, so `fills.py` effectively used the ~11:00 price. Carried onto the daily scale by daily close / last hourly close (accepted between 0.2 and 5), held inside the day's range. Without hourly bars the open is the previous close held inside the range (the research's `prev` mode), flagged `realOpen = false`.
- Calendar: a Sunday-to-Thursday day with at least 20 traded stocks (research), or a third of the stocks when fewer than 60 are stored. Days after the last known session are assumed to be Sunday-to-Thursday (future holidays are unknown).
- Universe: listed >= 180 calendar days (first stored session), traded on >= 57 of the last 60 calendar sessions, top 80 by 30-session turnover, ties by code.
- Incremental daily downloads re-read 40 days before the last stored session; a split newer than the one seen at the last full download triggers a full re-download (Yahoo re-scales history). A stock with no legacy feed is asked again after 7 days. Hourly: only the universe's stocks, `1mo` the first time then `5d`.
- EGX30 = Yahoo `^CASE30` (the symbol in the research's `case30_1h.json`), hourly; `2y` on the first run (two years of history at once), `5d` after. Session = first open, max high, min low, last close.
- Arab Finance feed = `https://www.arabfinance.com/ar/home/tagresult?slug=724` (from the research memory). Parser reads wording, not layout: ticker in brackets, "بواقع/بقيمة N جنيه|قرش", "اعتبارا من <date>" (d/m/yyyy, yyyy/m/d or "19 نوفمبر 2026"), "الجمعية العامة ... المنعقدة <date>"; Arabic-Indic digits and hamza/tanween variants normalised. A truncated date (the page prints "12/11/202" once) is left null, not guessed.
- Last cum-day = payment day minus 3 sessions: on 9 real 2025-26 dividends Yahoo's ex-day was always 2 sessions before Arab Finance's payment day. When Yahoo records the ex-day, the last cum-day is taken from it (no longer estimated).
- Yahoo check: a Yahoo dividend within 7 days of the expected ex-day; CONFIRMED within 2% of the amount, else AMOUNT_DIFFERS; NOT_ON_YAHOO once 5 sessions past the ex-day with nothing; WAITING otherwise.
- Same dividend: same stock and payment days within 7 days, or anchors (payment, last cum-day, assembly, first news) within 120 days in a sensible order. Hand-set or corrected entries (`byHand`) are never changed by a refresh; cancelling marks, never deletes (and still absorbs later news of it, so it is not re-added).

### E5: Arabic tables (session D)

Item 38 of `docs/egx-build-list.md`, written 2026-11-14. Files: `app/src/main/assets/egx/names_ar.tsv` (220 codes, every
row of EGX Analyzer's `yahoo_symbols.json`) and `app/src/main/assets/egx/strings_ar.tsv` (435 rows, of them 38 glossary
rows). The owner reads both before the install (item 41).

- **Key.** `strings_ar.tsv` is keyed by the English source text, as asked; the lead maps it to the final keys at item 37.
  Kotlin templates are written with `%1$s` / `%1$d` placeholders in the English column, in the order the Arabic uses.
- **Register.** Labels and buttons follow the accepted mockup (اشترِ، بِع، وضعتُ الأمر، الحساب الورقي، أوامر مجانية).
  Sentences use light Egyptian spoken Arabic as Egyptian trading apps do (مفيش، دلوقتي، النهارده، بكرة، استنى). The
  mockup has two Modern Standard words (غدًا، هذا الشهر); they are kept as written there. If the owner wants one register
  throughout, this is the first thing to change.
- **Counting.** Arabic plurals depend on the number, so the Today sentence is built from parts: one (سهمًا), two (سهمين),
  3 to 10 (%1$d أسهم), 11 and more (%1$d سهمًا). The mockup's "غدًا: اشترِ سهمين وبِع سهمًا." is kept as a full row.
  After a number on cards the app writes "390 سهم", as the mockup and Egyptian broker screens do.
- **Market terms.** كوبون for dividend; تاريخ نهاية الحق for the last day that carries it; صرف الكوبون for payment;
  وقف الخسارة; الهدف الأول / الهدف الثاني; نطاق الدخول; الحد السعري for the price limit; وحدة تغيّر السعر for the tick;
  قيمة التداول for turnover; فرق السعر for spread; ضريبة الدمغة; الكاش for the cash yardstick (mockup) and صندوق النقد
  for the fund; المؤشر for the EGX30 (mockup), with "EGX30" written in Latin where it is named in full.
- **Strategy names.** Dividend run = قبل الكوبون (the mockup's card line; plainer than a literal "run"). Calm stocks =
  الأسهم الهادئة (mockup). Dividend payers = الأسهم الموزّعة. Slow breakout = الاختراق البطيء; Fast breakout =
  الاختراق السريع. Low-risk formula = معادلة المخاطرة القليلة.
- **Tiers.** Proven مُثبتة, Promising واعدة, Watch تحت المراقبة, Testing تحت التجربة, Failing متعثّرة.
- **Thndr.** ثاندر inside Arabic sentences; Latin "Thndr" is fine on a button with its logo. The app name "Signal Lab"
  stays in Latin letters.
- **Pounds.** ج after an amount on cards and tiles (mockup "12,500 ج"); جنيه in sentences.
- **Dates.** Egyptian month names (يناير … ديسمبر), day before month ("15 نوفمبر").
- **Crypto rows.** The crypto app's shared screens are included with note `crypto`, so it can get Arabic later; texts
  that are only test tags, file names or code were left out.
- **Company names.** Written as the exchange and the financial press write them, mostly the registered Arabic name, with
  the brand after a dash where Egyptians use it (موبكو، سوديك، إيبيكو، كابو). Codes that share one company get the same
  name: EFHI/EFIH, FAIT/FAITA (with بالجنيه / بالدولار), KRDI/NAKH, QNBA/QNBE, VLMR/VLMRA.
- **Names I am not sure of** (best guess written; the owner or EGX's site should confirm): ACAMD, ACAP, ACTF, AIHC,
  AMII, ASPI, CFGH, COPR, CPME, CRST, EGAS, GMCI, GPIM, GRCA, GTEX, GTWL, ICID, IDRE, LUTS, MBEG, NARE, NDRL, PHGC,
  RREI, UEGC, VLMR/VLMRA, KRDI/NAKH (which of the two codes is current), EFHI (likely an old code of EFIH).

### E3: the second app, the evening pass and the ledger

- Item 22: the EGX app is its own module `:egx` (id `com.ikverse.signallab.egx`, "Signal Lab EGX", green rising-line icon
  on the light page), depending on `:engine` only. Product flavours of `:app` were rejected: they rename the crypto app's
  tasks and APK path, which CI, the release workflow and `CLAUDE.md` rely on. Session B's data layer and session D's
  tables moved into it (package `com.ikverse.signallab.egx.data`); nothing of the crypto app changed. CI also runs
  `:egx:testDebugUnitTest`. Same signing key as the crypto app.
- The board: `pass/BoardSource.kt` lays B's cleaned series on the calendar as `prepare.py` did. Past dividends come from
  Yahoo (announced 17 sessions before the ex-day, as researched); upcoming ones from Arab Finance (announced at the
  assembly, ex-day = the session after the last cum-day); one dividend on both counts once; yield against the raw close.
- Holidays known ahead (needed for month ends): the past's closed weekdays, Egypt's seven fixed public holidays, and the
  owner's own list in Settings (the Eids and Sham el-Nessim move every year). B's calendar assumes plain weekdays after
  the last session; the engine's board uses this table instead.
- Item 23: an exact alarm at 15:45 Cairo, Sunday to Thursday, starts a WorkManager job. If the session's candle is not
  on Yahoo yet it retries every 30 minutes, 7 times (to 19:15). On a day off the pass works from the last session.
- Item 24: the ledger (`egx-ledger.db`, Room) is append-only: each evening re-runs every strategy from the session the
  ledger started on and appends only sessions after the last stored one, so a later price correction cannot rewrite it.
  Four records per strategy: with the levels, by the rule alone, and both at double costs. Costs by rank: 0.5% for the
  30 busiest, 0.8% for the rest. Yardsticks (cash, EGX30, 20 random twins' tenth/middle/ninetieth percentiles) are
  stored per evening.
- Item 25: each strategy's paper account starts with money / (strategies on); a strategy that is not on is recorded at
  the same share for comparison. Monthly lists first buy at the first month end after the start, as tested.
- The paper fills buys at the next session's open (as the engine and the research do), while a call advises placing a
  buy near the close: the "My copies" record will show which was right (a question of the plan).
- Calls: one slot of the paper account in pounds and whole shares; levels from the close and the 14-session ATR,
  rounded to 0.01; "wait a day" after a jump of 8%; sells at the open; "keep" rows on a month-end list.
- Item 26: settings in the app's preferences (money, cash rate 19%, strategies on, language, digits, double costs,
  holidays). Item 27: four notifications (tomorrow's plan, the month-end list, targets and stops reached, dividend dates).
- Item 37's format decided here: every text is written in English in the code and looked up by that exact English in
  `strings_ar.tsv` (D's table), so no key list has to be kept in step; a missing text shows in English and is listed.

### E4: screens (part 1: the theme and Today)

- Item 28: `view/Theme.kt` carries every colour of the accepted mockup, light and dark, and the mint and sky wash. Readex
  Pro is not in the app yet: its font file must be downloaded from Google Fonts, which waits on the owner's OK; the
  phone's sans-serif stands in (`Type.Family` is the one place to change).
- Item 29: Today as in the mockup: the plan's date and the market pill, the one sentence, swipeable call cards tinted for
  buy or sell with "placed the order", the paper-account tile against the EGX30 and cash, the free-orders tile, and the
  floating pill menu. `todayView` builds every text without Android, so tests read them.
- Items 35 and 36 started: folded, unfolded (a window 600 dp wide or more: sentence and tiles on one side, cards on the
  other) and half-folded (a horizontal crease: sentence and tiles above, cards below), from the window and Jetpack
  WindowManager's fold; the four pages swipe sideways and the pill menu jumps between them. Strategies, Ledger and
  Settings show a placeholder until the owner accepts their design in session C (items 31 to 34).
- Texts follow session D's English exactly ("EGX30 %1$s", "%1$d shares", "EGP %1$s"), with Arabic's counted forms
  (1, 2, 3 to 10, 11 and more) and " و" to join the sentence's parts. One row was missing and is added, marked for the
  owner: "Plan for %1$s" = "خطة %1$s" (the mockup's wording). A test holds Today's and the notifications' texts to
  having an Arabic row.

### E4: screens (part 2: the screens accepted in session C)

- Readex Pro downloaded from Google Fonts on the owner's OK (variable TTF, SIL Open Font Licence in
  `assets/licences/ReadexPro-OFL.txt`), one file for every weight.
- Items 31 to 34 built from the five screens the owner accepted at version 1 in session C
  (https://claude.ai/artifact/AsCAdWNe48c1sX8iDPUhoF): the call page (levels ladder, amount with costs and costs at
  double, timing, dividend dates, reason; "Copy amount and open Thndr" and the "I took it" sheet saving to My copies),
  Strategies grouped by tier with an evidence page (research figures from docs/egx-research.md), Ledger (both records,
  by strategy, watched ones, costs, dividends, My copies), Settings (money, cash rate, strategies on, language,
  digits, double costs, the holiday list, run now), and the month-end list. Unfolded, each screen splits in two panes.
- Where the mockup and the plan or tables differ: Arabic strategy names come from session D's table (قبل الكوبون, not
  the mockup's سباق الكوبون); the count of versions tried is the registry's 170 (the mockup said 176); the mockup's
  "1.66 a share after the 5% tax" on a Dividend run call is left out, because the run sells at the open of the last day
  that carries the dividend and does not collect it (session C's open question); the page says so instead.
- Thndr's package name is not published: the known candidates (`com.thndr.app`, `com.thndr`, `app.thndr`) are tried,
  else the browser opens thndr.app. Unverified until it runs on the Fold.
- Calls now store their round trip and the normal day's move (the call page's costs line and levels note). The ledger
  database has never been installed, so its first version changed without a migration.

### E5 and E6 so far

- Item 37's completeness is a test: `ArabicTableTest` reads every literal `t("...")` text of the app's sources (plus the
  strategies' names and lines, the menu and the counted forms) and fails on any without an Arabic row. 87 texts of the new
  screens had none; session D is translating them. Placeholders must match between the languages (also tested).
- Items 39 and 40: the whole app is right to left in Arabic; bar charts, the levels ladder and the copies table stay
  left to right; tickers stay Latin; language and Arabic-Indic digits are in Settings.
- Item 42: `CLAUDE.md` has the EGX app's layout, commands and agreements.
- Item 43: `FullEveningPassTest` runs a whole evening pass on a PC against the research's recorded downloads (191
  stocks, hourly bars, the EGX30, the recorded Arab Finance page) with the ledger started a year back. It found one bug,
  fixed: the random twins' growth was measured against the pound share instead of 1.00. A second pass the same evening
  appends nothing.

### E5: Arabic for the screens (session D)

- 88 rows appended to `egx/src/main/assets/egx/strings_ar.tsv` for the finished screens' texts (the lead's list held 88
  lines, not 87); no existing row changed. Same register and glossary as before; placeholders match in both columns.
- Near-twins reuse earlier wording: the strategy one-liners, the stop and target notifications (now "اتباع النص عند"
  for a half already sold), "Last day to hold" = آخر يوم تحتفظ فيه, "Cash date" = تاريخ صرف الكوبون.
- The Dividend run text says the call sells at the open of the last day to hold, so the gain is the rise before the
  dividend, not the dividend itself.
- "Evidence ›" is written "الدليل ‹": the arrow points the reading direction in Arabic; the lead may prefer an
  auto-mirrored icon instead. "yyyy-mm-dd" in the holidays help stays in Latin, as typed.
- `ArabicTableTest` is not committed on `egx` yet (it sits uncommitted in the lead's checkout), so it could not run in
  this worktree. The same two checks were run in Python against the lead's current sources: all 147 `t("...")` texts
  have a row, and placeholders match in all 524 rows.

### Auto-update from GitHub (approved 2026-11-14, with the first release)

- The crypto app's updater (`update/`, six files) copied into `egx/.../egx/update/` with its tests; only the package and
  the release URL changed (`api.github.com/repos/ikverse/Signal-Lab-EGX/releases/latest`). It reads GitHub's "latest"
  (never drafts or pre-releases), follows downloads only to GitHub's hosts, and checks the file's package, version and
  signing key against the installed app before Android's installer opens; the installer always asks.
- `REQUEST_INSTALL_PACKAGES` and a file provider for the update folder in the manifest. A quiet check runs after each
  evening pass and when Settings opens (at most once a day); an Updates card in Settings shows the version, the state and
  the button. GitHub's own error details show in English under a translated line, since they carry numbers and codes.
- Eleven Arabic rows for the card added by the lead in session D's register, marked "to check".
- The repository must be public for this: the updater reads releases without a token.

### 0.1.1: fixes from the owner's first look on the Fold (approved 2026-11-14)

- The page background and wash draw behind the system bars; only content is inset. `isNavigationBarContrastEnforced` off;
  a dark window background for dark mode (`values-night`).
- Titles broke mid-word because of negative letter spacing on joined Arabic letters: no letter spacing in any style now,
  and every style has a line height. Sizes reduced (the phone runs at font scale 1.15).
- The wash is two circles that fade out inside the screen (it was a gradient cut by a rectangle).
- The pill menu is 96% opaque with a shadow. Material's colour scheme is filled in from the design, so switches, fields
  and the sheet stop using default purple.
- Empty state: one line ("The record starts after the first evening pass.") replaces empty bars and dashes on Today,
  Strategies and the Ledger; the Ledger hides its record cards until then.
- Arabic: a number with its percent sign and year ranges are wrapped in left-to-right isolates; chart labels take their
  direction from their own letters. The owner rejected a rewrite into standard Arabic: the spoken Egyptian wording stays.
  Two rows changed: "Follow the phone" is now زي التليفون, and the new empty-state line (marked "to check").
- Checked on the Fold's cover screen by screenshot before release; the unfolded layout was not re-checked (phone folded).

### 0.2.0: five new features (approved 2026-11-14, branch `egx-features`)

The owner approved: a stock page with chart, a dividend calendar, a real-terms ledger (dollars and gold), a home-screen
widget, and the AI analyst through the PC host.

**Stock page**
- Opened by tapping the company on a call page, on the month-end list, or a row of My copies. The Dock hides while it is open.
- The chart is drawn on a Compose Canvas (no chart library): closes as a line, the levels of the newest buy as dashed
  lines while a strategy still holds the stock. Ranges 3 months (63 sessions), 1 year (245), all. Left to right in Arabic.
- Paper trades are rebuilt from the LEVELS fills: a trade runs from a buy until the shares are back to none, its result
  after costs. A new DAO query `callsFor(code)` reads the stock's calls; no schema change.
- Built with `ANDROID_HOME` set in the command: this checkout has no `local.properties`, and reading the other checkout's
  copy (it holds the signing settings) was refused. Debug builds need only the SDK path.

**Dividend calendar**
- A "Dividend calendar ›" link under Today's sentence and in the stock page's Dividends card. A month grid, Sunday to
  Saturday (the exchange's week), marks the days with a last day to hold or a cash date; the list beside or below it gives
  each date with the amount, the yield at the stock's last close (from the last 30 days stored) and a chip when a
  Dividend run trade holds the stock on paper.
- Cancelled dividends are left out. Only dividends already collected are read; nothing new is downloaded.
- "Add to my calendar" sends Android's ACTION_INSERT with an all-day event: the phone's calendar app opens filled in and
  the owner saves it there. No calendar permission.

**Real-terms ledger**
- Two Yahoo daily series, no key: `EGP=X` (the dollar in pounds, the official rate) and `GC=F` (the front gold future in
  dollars an ounce). The first download reaches back four years; later ones re-read the last ten days. A failure goes to
  the sync report's failures and the pass goes on.
- `egx.db` goes to version 2: a new `macro` table, added by `CREATE TABLE IF NOT EXISTS` on upgrade. Nothing else in the
  file changes, so the owner's hand-typed dividends stay (tested on a version-1 file).
- The Ledger is still stored in pounds only; dollars and gold are worked out on display, so the ledger stays append-only.
  A pound growth g becomes (1 + g) · m(start) / m(end) − 1 in a measure costing m pounds a unit; the value used for a day
  is the last close on or before it. Gold is counted in grams (31.103 g an ounce).
- "Held dollars" and "Held gold" join the account's bars, beside cash and the EGX30. The measure choice and the
  "In real terms" card appear only once the dollar is known for the ledger's first day; a series that stops short of the
  ledger's last day is named with the date it reaches.

**Home-screen widget**
- Glance 1.1.1 (`androidx.glance:glance-appwidget`, already in the machine's Gradle cache) added to the catalog and to
  `:egx` only.
- The widget reuses `todayView`, so its sentence, date line and figures are the same as Today's. Two sizes: small shows
  the date and the sentence; wide adds the count of buys and sells and the account against the EGX30. Colours are the
  design's, with night variants. A tap opens the app.
- Android's periodic refresh is off (`updatePeriodMillis="0"`). It is redrawn after each evening pass that writes calls
  (the call sits in `EveningWorker` in `Schedule.kt`, not in `EveningPass.kt` as the list said, because the worker is
  where the pass's outcome is handled), and whenever the app sees a change of settings or of tonight's calls.
- The widget's layout direction follows the phone's language, not the app's; its texts follow the app's.

**AI analyst through the PC host**
- `analyst-host` needed no change: the phone sends its own model, system prompt, history and tool list with each run, and
  the host bridges any tool named in lower case letters and underscores. The crypto app is not touched.
- `egx/analyst/EgxHost.kt` copies the crypto app's host client and pairing (HTTPS pinned to the PC's certificate, the code
  sent with every request). The address and pin are in the EGX app's own SharedPreferences; the code is sealed with an
  AES-GCM key in Android's Keystore under the EGX app's own alias, in the no-backup folder.
- Five read-only tools, run on the phone: `ledger`, `calls`, `stock`, `dividends`, `research`. They answer in JSON, dates
  as yyyy-mm-dd, money in pounds. Nothing the analyst can call writes anything.
- Model `claude-opus-5-5`, as the crypto app's default; no model choice on this screen. A run's budget ceiling is $3 at
  API prices, as Claude Code measures it (on the owner's plan it only stops a runaway run).
- The analyst runs in the app's own scope, so swiping to another page does not stop it; Stop cancels it on the PC too.
  A conversation keeps its turns until "New conversation". The system prompt asks for Egyptian Arabic when the app is in
  Arabic.
- Answers are kept in a new Room table `analyst_answer`; the ledger database goes to version 2 by a migration that only
  creates that table (tested on a file built from Room's own version-1 schema, with a saved copy kept).
- "Analyst" is the menu's fourth item, before Settings; the menu's items are 3 dp narrower each so five fit folded.
  Not checked on the Fold's cover screen.
- The host's error messages show in English under a translated line, as the update card does.

### Release 0.2.0 (approved 2026-11-14)

- The release workflow built the crypto app (`:app`, checked against `app/build.gradle.kts` 2.5.0), so the `v0.1.0` and
  `v0.1.1` runs failed and those releases were uploaded by hand from a signed local build. It now releases the EGX app:
  the tag is checked against `egx/build.gradle.kts`, the tests are `:engine:test :egx:testDebugUnitTest`, the APK is
  `:egx:assembleRelease` signed with the existing secrets, and the notes come from `egx/CHANGELOG.md`.
- New step: the APK's signing certificate must match the previous release's APK, or nothing is published. The 0.1.x
  APKs were signed on the owner's PC; a different key in the secrets would leave every install unable to update.
- Final release, not a pre-release. None of the five features had been tried on the Fold when it was tagged.

### EGX: pairing by scanning ClaudeIn's QR code (approved 2026-11-15)

- The Analyst tab's pairing card has a "Scan ClaudeIn's QR code" button above the typed address and code. It reads the
  code ClaudeIn's "Pair a device…" window already shows (`qr_payload` in ClaudeIn's `windows.py`): addresses, port, code
  and the certificate's full SHA-256. Nothing in ClaudeIn or the old analyst host changed.
- Scanner: Google's code scanner from Play Services (`play-services-code-scanner` 16.1.0). It shows Google's own camera
  screen, so the app asks for no camera permission and carries no camera code. A manifest entry has Play Services fetch
  the scanner when the app installs, not at the first scan. Phones without Play Services can still type.
- A scan pairs with no "Does the fingerprint match?" step: the fingerprint came straight off the PC's screen, and the
  phone pins it from the first request, so a PC sending another certificate is refused before the code is sent.
- Each address in the code is tried, Tailscale's (100.64.0.0/10) first, because that one also works away from home;
  the first that answers is the one kept.
- The pairing card's note now points at ClaudeIn instead of `start-analyst-host.cmd`; its Arabic row was replaced and one
  added (to check). Not tried on the Fold; the crypto app's pairing screen is unchanged.

### EGX: the QR scan failed in the release build (approved 2026-11-15)

- 0.2.1's "Scan ClaudeIn's QR code" stopped with "getClass() on a null object reference" before the camera opened. Debug
  builds and the tests were fine. In the release build the shrinker (R8) had copied Play Services' and ML Kit's start-up
  code into the app's scan and turned not-null checks into `getClass()` calls, and one of those got a null. The exact
  call was not pinned down; the phone was not connected.
- Fix: `egx/proguard-rules.pro` keeps `com.google.android.gms.**` and `com.google.mlkit.**` as Google ships them. The
  release's code grew from about 4.0 MB to 4.9 MB; the rebuilt release now calls `GmsBarcodeScanning.getClient` and
  `startScan` directly. Not yet tried on the phone.
- A pairing failure that is not the host's own refusal now also writes its stack trace to the phone's log
  (`adb logcat -s SignalLabEgx`), so the next fault like this shows its line.

### EGX: technical picks (approved 2026-11-16; mid term without targets and the volume filter, 2026-11-17)

- Stage 1 (`f4a9cb9`): `tools/egx/technical.py` and `docs/egx-research.md` section 4. The owner chose both lists, the
  mid-term one without targets. The short-term version keeps "volume at least its 20-day average": without it 2015-22
  fell from 5.59 to 2.89 (the first list sent for approval wrongly said "no volume filter").
- The engine's re-run gives the script's short-term figures exactly. Mid term gives 5.89 against 5.62 in 2015-22: the
  script kept a pick waiting with no time limit when the account had no cash for it; the engine drops every pick after
  three sessions, as the card says. The script with that one change gives the engine's figures.
- The learning step is monthly (the plan said monthly; the research's walk-forward was yearly): each month uses the
  version with the best account over all the history before it, from 2010; the registered version until a year of
  history exists, and on ties. From 2015 that gives short term 2.83 and 3.77, mid term 5.78 and 3.50.
- "Re-weight the ranking" (plan item 7) is the version choice itself: the ranking inside a version is the research's and
  stays fixed, so nothing untested is used.
- Switching off follows the strategies' rule: after 12 months live, behind cash and the EGX30, or under the tenth
  percentile of 20 random twins. A switched-off list is still recorded.
- The picks have their own start date (`picksStart`, the first evening after the update), so no picks are written for
  evenings the owner never saw. Their paper account is eight slots of the amount per pick (10,000 pounds to start),
  never part of "money I trade with".
- Fundamentals: stockanalysis.com's statistics page is server-rendered and has P/E, net income, EPS, total debt and debt
  to equity for the EGX stocks tried (COMI, ELKA, NEDA; "n/a" where it has none). Read only for the evening's shown
  picks, at most once a week a stock; shown on the card, never used to choose (the loss filter could not be tested).
- News: Arab Finance's company news list (`/ar/news/newscompany?key=CODE`) is server-rendered; its events calendar is
  filled in by script, so the results, assembly, capital-increase and dividend flags come from the stock's own
  headlines of the last 10 days. Each pick row keeps whether it had news, for a later test of news against no news.
- Share volume comes from Yahoo's daily volume (0 on a session without trades); a board without it estimates it as
  turnover over the close.
- Today: the section sits under the paper-account tiles folded, under the calls unfolded, and under the calls in
  tabletop, where the three-column grid became rows of three so the picks can follow in one scroll.
- A pick card's "backtest" record is the trades of each month's chosen version before the list went live; "live" is
  the picks' own account.
- Notifications: one for the evening's picks; one for each shown pick that reached a target or closed under its stop.
- The pass takes about 14 seconds on the PC with the picks (`FullEveningPassTest` on the recorded data). Not tried on the
  Fold. The 88 Arabic rows are to check.
