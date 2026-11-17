# Signal Lab Android

A native Kotlin and Compose app that gives daily calls from a small set of research-backed crypto strategies (Binance spot,
long-only, daily closes), each with its evidence, and keeps an honest paper ledger of whether each strategy beats random
entries after fees. Research tool, not financial advice: no real money is ever traded.

Signal Lab 2 (branch `v2`, plan in `docs/v2-plan.md`, evidence in `docs/strategy-research.md`) replaced Signal Lab 1's
screens and patterns; Signal Lab 1's record is imported once into the new ledger. The Python and Termux version
(`ikverse/Signal-Lab`) is retired; its one remaining job was exporting test data once (see `tools/`). Minimum Android 10.

## Working agreements

- **Never implement without approval.** State the change as a list and wait for the literal word
  "approve". "ok", "do it" and a refinement are not approval.
- **Standing approval, granted 2026-10-16: Signal Lab 2, milestones M2 to M9 of `docs/v2-plan.md`.**
  Build them back to back on branch `v2` without a new "approve" per change. Commit each milestone on
  `v2` once its tests pass; never push. Solve implementation problems without asking and log each
  decision in `docs/CHANGELOG-ai.md`. Still stop and ask for: (1) installing on the phone (first at M5;
  the user backs up 1.7.0 and connects the Fold7); (2) a change of product or scope the plan does not
  already decide; (3) any push or release tag; (4) the Claude API key at M8; (5) anything that deletes
  user data.
- **Build plus unit tests is the loop.** No emulator and no screenshots unless asked. Install on the
  connected phone and hand over.
- **Push and release are separate.** Push = commit and push `main`; CI runs the tests and nothing
  ships. Release = bump the version, add the `CHANGELOG.md` section, commit, tag `vX.Y.Z`, push the
  tag **explicitly** (`git push origin vX.Y.Z`; lightweight tags don't travel with `--follow-tags`).
  A `CHANGELOG.md` heading ending in `(pre-release)` publishes a GitHub pre-release, which no installed
  copy is offered; mark it final with `gh release edit vX.Y.Z --prerelease=false` once it is verified.
- Filter Gradle output; raw build logs are the largest source of context growth.

## Commands

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
./gradlew :engine:test :app:assembleDebug 2>&1 | grep -E "^e:|^w:|FAILED|BUILD"
```

`local.properties` (gitignored) holds `sdk.dir` and the signing settings. **Write it with exactly
`C:\\Users\\...` (doubled backslashes)**: a single backslash is read as an escape and the build dies
with "Invalid file path".

```bash
export ADB="$HOME/AppData/Local/Android/Sdk/platform-tools/adb.exe"
timeout 60 "$ADB" -s <serial> install -r --user 0 app/build/outputs/apk/debug/app-debug.apk
```

Wrap every adb call in `timeout N`.

## Layout

- `engine/` — plain Kotlin, **no Android**: candles, indicators, regime, random baselines, statistics (`core` package),
  `strategy/` (the `Strategy` interface and built-ins), `eval/` (fills, baseline, backtest, live rules). Its tests run on a
  PC in seconds. `allWarningsAsErrors` is on. Test helpers and golden data live in `src/testFixtures`.
- `app/` — `data/` (Binance, candles, universe, futures), `ledger/` (Room ledger, importer, preferences), `runner/` (daily
  pass, worker, alarm, notifications), `model/` (screen state), `view/` (Compose screens), `update/`. Depends on `:engine`.
- `analyst-host/` — the analyst host for the owner's PC (JDK only, no Android): runs the installed Claude Code headless for the
  phone, with Signal Lab's tools bridged back to the phone. Start it with `tools\start-analyst-host.cmd`. Its live test runs
  only with `SIGNAL_LAB_LIVE_CLAUDE=1`, because it uses the plan's usage.
- `research/` — the whole-account test for the owner's PC (JVM, depends on `:engine`, not part of the app): every daily
  strategy over years of Binance history, followed as one account (`docs/strategy-research.md` §8). Download history with
  `python tools/download_history.py klines`, then `./gradlew :research:run`. Its results are copied by hand into
  `engine/.../eval/AccountRecord.kt`, which the tiers and the evidence pages read.
- `egx/` — **Signal Lab EGX**, the second app (`com.ikverse.signallab.egx`, plan `docs/egx-plan.md`, build list
  `docs/egx-build-list.md`, evidence `docs/egx-research.md`): evening paper calls for the Egyptian Exchange, copied by hand
  on Thndr. Depends on `:engine` only; the crypto app is never touched by it. `data/` (Yahoo, Arab Finance, the store),
  `pass/` (the board, the evening pass, schedule, notifications), `ledger/` (Room, append-only), `i18n/` (language
  tables), `view/` (Compose, "Calm with cards"). Its rules live in the engine's `egx/` package.
- `tools/` — one-off scripts, not part of the build. `tools/egx/` holds the EGX research scripts.

## Signal Lab EGX

```bash
./gradlew :engine:test :egx:testDebugUnitTest :egx:assembleDebug 2>&1 | grep -E "^e:|^w:|FAILED|BUILD"
python -I tools/egx/export.py research/data/egx/data_typical <dbn2.json> research/data/egx/raw
./gradlew :research:run --args="egx research/data/egx/data_typical"        # the research's table, re-made (add "wait")
./gradlew :research:run --args="egx technical research/data/egx/data_typical"   # the technical picks, re-made
```

- Texts are written in English in the code (`tr.t("...")`) and looked up by that exact English in
  `egx/src/main/assets/egx/strings_ar.tsv`; `ArabicTableTest` fails when a text has no Arabic row. The owner reads the
  Arabic before an install.
- Month ends need tomorrow's date, never tomorrow's candle: the board knows the exchange's holidays ahead (the past's
  closed weekdays, the fixed public holidays, and the owner's list in Settings for the Eids and moved holidays).
- The ledger is append-only: each evening re-runs every strategy from the ledger's start and stores only new sessions.
- `FullEveningPassTest` runs a whole pass on the research's recorded downloads when `research/data/egx/` is present.
- Technical picks (`docs/egx-plan.md` part 8): the engine's `Technical.kt` (chart indicators, `ShortTermSetup`,
  `MidTermSetup`, `ChartLevels`, `PickAccount`, `PickLearning`, `MarketState`), registered in `EgxStrategies.technical`;
  the app's `pass/TechnicalEvening.kt`, `view/Picks.kt`. Company facts come from stockanalysis.com and headlines from
  Arab Finance's company news, only for the evening's picks. The picks are never part of "money I trade with".
- Not built from the build list without the owner's word: an install, a push, a tag or release, deleting user data.

## The engine's rules

- A signal on bar *i* uses only bars up to *i*. Cutting history at *i* must give the same answer for bars up to *i* as the
  full history. Tested for every registered strategy.
- A call enters at the **next** candle's open. Each strategy has its own exit plan (a safety stop in ATR, its own exit
  signal filled at the next open, a time limit); the stop is checked first, and a gap fills at the open.
- Costs: 0.25% round trip for BTC and ETH, 0.40% for everything else, unless changed in Settings.
- Random baselines come from the same coin, ±90 days, same BTC regime, same exit plan. Their generator is counter-based
  (splitmix64), so a draw never depends on iteration order.
- Every strategy is registered before it runs; the count of everything ever tried is the multiple-testing denominator.

## Signing

The key is the update: Android refuses an update signed by a different key. It lives in GitHub secrets
(`SIGNAL_LAB_KEYSTORE_BASE64`, `SIGNAL_LAB_KEYSTORE_PASSWORD`, `SIGNAL_LAB_KEY_ALIAS`) and locally in
`local.properties` as `SIGNAL_LAB_KEYSTORE_FILE` and the same two names. **Back the keystore up
somewhere safe**; losing it means every install needs an uninstall.
