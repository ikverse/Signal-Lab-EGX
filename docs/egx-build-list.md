<!-- Written 2026-11-14 at the owner's request. The full build list for Signal Lab EGX: `docs/egx-plan.md` (what and
     why) turned into numbered work, with the accepted design. NOT approved yet: nothing here is built. The session
     that builds it posts this list, waits for the literal word "approve" once, and then runs it to the end. -->

# Signal Lab EGX: the full build list

Sources: `docs/egx-plan.md` (the plan and its numbers), the accepted mockup
https://claude.ai/artifact/6qDLTnGJke5fcWiXXXjPhX ("Calm with cards"), and the research scripts, which today sit only
in a temporary folder (see item 1).

## Rules for the whole build

- One "approve" covers items 1 to 46. Work happens on branch `egx`; each milestone is committed when its tests pass;
  nothing is pushed, tagged or released.
- The crypto app must keep building and passing its tests after every milestone. Its ledger, schedule, data and
  screens are not changed, except that shared texts move into language tables (item 37).
- Stop and ask the owner for: installing on the phone; anything that deletes user data; a push or release; a change
  the plan does not already decide; and item 12 if the engine disagrees with the research.
- Every implementation decision is logged in `docs/CHANGELOG-ai.md`. Reports to the owner are short and plain.

## E1. Research and engine (lead session)

1. Copy the research scripts into `tools/egx/` from
   `C:\Users\Me\AppData\Local\Temp\claude\4ce434ddcd2c5e5b\504d3a53-f0b1-4272-bebd-e0a0b3d5f85a\scratchpad\egx\`
   (`download.py`, `hourly.py`, `prepare.py`, `optimistic.py`, `proxy.py`, `adapt3.py`, `harden.py`, `wf.py`,
   `divacct.py`, `levels.py`, `events.py`, `divwin.py`, `overnight.py`, `hourly_study.py`, `fills.py`). If the folder
   is gone, rewrite the download and cleaning scripts from `docs/egx-plan.md` part 2 and say so.
2. Write `docs/egx-research.md`: the findings and sources of the six research passes and the levels test, taken from
   `docs/egx-plan.md` and the project memory `egx-adaptation-research-2026-11`.
3. Engine: a market's settings in one place (245 trading days a year, costs by liquidity, the cash rate), so nothing
   assumes 365 days or Binance's fees.
4. Engine: idle money in the account earns the cash rate; dividends are paid in after the 5% tax.
5. Engine: a stock locked at its price limit, or not traded, cannot be filled; the fill waits.
6. Engine: monthly and quarterly lists that keep a stock while it stays on the list (no sell-and-rebuy).
7. Engine: a dated event as a trigger (a dividend's last day), fed in the way funding is today.
8. Engine: the four levels on every call (entry range, stop at four moves, targets at five and ten, half sold at
   target 1), plus the second record "by the rule alone".
9. Engine: the five strategies of the plan (Dividend run, Calm stocks, Dividend payers, Slow breakout, Low-risk
   formula) and the fast Breakout as a silent control; all registered, with the 170 research versions counted.
10. Engine: the yardsticks (cash, the EGX30, eight random stocks picked the same days) and the tier rule against them.
11. The whole-account test (`research/`) runs on EGX data from `tools/egx/`.
12. Re-make the plan's results table with the engine. If a result differs from the research by more than a fifth,
    stop and tell the owner before going on.
13. Unit tests for items 3 to 10, including "cutting history gives the same answer" for every new strategy.

## E2. Egyptian data on the phone (parallel session B, new files only under `app/.../data/egx/`)

14. A Yahoo client: daily candles by the legacy symbol, hourly candles by the ISIN symbol, merged per session.
15. The stock table (EGX code, Yahoo names, Arabic and English company names), seeded from EGX Analyzer's
    `yahoo_symbols.json`.
16. Cleaning: no-volume rows dropped, one-day moves beyond 21% flattened, Yahoo's "open" never used, the real open
    taken from the hourly candles.
17. The universe: the 80 busiest by 30-session turnover, listed 180 days, traded on 57 of the last 60 sessions.
18. The EGX30 built each day from its hourly candles and stored from the first run on.
19. Upcoming dividends read from Arab Finance's dividend news page, checked against each stock's dividend table, with
    add and correct by hand.
20. The trading calendar taken from the candles themselves (Sunday to Thursday, holidays as missing days).
21. Unit tests with recorded responses; no test calls the network.

## E3. The second app, the evening pass and the ledger (lead session)

22. A second app from the same repository: its own name ("Signal Lab EGX"), id, icon and data, sharing the engine.
23. The evening pass, Sunday to Thursday after the close, with a retry if the day's candle is not there yet.
24. The ledger in pounds: calls, fills, dividends, the two records, costs at 0.16% plus the spread (0.5% in all for
    the 30 busiest, 0.8% for the rest), shown also at double.
25. The paper account split equally among the strategies that are on; the split is fixed.
26. Settings: "money I trade with", the cash rate (19% to start), which strategies are on.
27. Notifications: tomorrow's plan in the evening, the month-end list, dividend dates, a target or stop reached.

## E4. Screens in the accepted design (lead session, after item 30)

28. The theme: Readex Pro; light page #F3F6F4, cards white, text #0F1513, buy #0B8F63, sell #D6453D; dark page
    #0B0E0D, cards #181C1B, text #EEF2F0, buy #3DD9A0, sell #FF7A72; the soft mint and sky wash at the top.
29. Today: the date and market line, one plain sentence, swipeable call cards tinted for buy or sell (action,
    company, shares, pounds, when to place it, "placed the order"), the account tile and the free-orders tile, and
    the floating pill menu (Today, Strategies, Ledger, Settings).
30. Parallel session C (design only, no code) draws the call page, Strategies, Ledger and Settings in the same
    style; the owner accepts them there before items 31 to 34 are built.
31. The call page: the four levels, amount, timing, dates, the reason and the strategy's record, "Copy amount and
    open Thndr", "I took it" with the real price.
32. Strategies: each one's tier, what it does, its evidence page and its record against the three yardsticks.
33. Ledger: the paper account against cash and the EGX30, by strategy, "My copies", the two records.
34. The month-end list (sell, buy, keep) and the counter of the month's 50 free orders.
35. All three Fold 7 states for every screen: folded; unfolded with the sentence and tiles on one side and the cards
    on the other; half-folded with the sentence and tiles above the crease and the cards and menu below it.
36. Swiping moves between the four pages and through the call cards.

## E5. Arabic and English

37. Every text of the shared parts and of the EGX app moves into language tables; the crypto app keeps its English.
38. Parallel session D (new files only): the Arabic table for every text, in plain Egyptian market Arabic, and the
    Arabic company names for the stock table.
39. Right-to-left layout everywhere; charts and price tables stay left to right; tickers stay in Latin letters.
40. Settings: language (Arabic, English, follow the phone) and Arabic-Indic digits on or off.
41. The owner reads the Arabic texts and corrects them before item 44.

## E6. Finish

42. `CLAUDE.md` gains the EGX app's layout, commands and working agreements; `docs/CHANGELOG-ai.md` is complete.
43. Both apps build; every unit test passes; a full evening pass runs on a PC against recorded data.
44. Stop: ask the owner before installing on the Fold 7.
45. After the install: one real evening pass on the phone, checked with the owner.
46. Stop: no push, tag or release without being asked.

## E7. Technical picks (approved 2026-11-16, mid term without targets 2026-11-17; branch `technical`)

47. Done: `tools/egx/technical.py`, the backtest of both lists as whole accounts, walk-forward, against cash, the market
    and random stocks; results in `docs/egx-research.md` section 4; the tried count grows by 70 to 240.
48. Done: chart indicators in the engine (`Chart`: prior highs and lows, 20-day average volume, 10-day range and the
    stock's own tightest fifth and tenth, swing highs); share volume on the board.
49. Done: `ShortTermSetup` and `MidTermSetup`, 8 versions each, the registered one first; `ChartLevels`.
50. Done: `PickAccount`, the picks' paper account, with each pick's outcome (bought, target 1, target 2, stop, time
    limit, exit average, expired) and `PickStats`.
51. Done: `PickLearning`, the monthly choice from all the history before the month; `MarketState`.
52. Done: both lists registered in `EgxStrategies.technical` as Testing; `EgxTiers.judge` for a list.
53. Done: the research run reproduces the script (`--args="egx technical <dir>"`); no-lookahead tests for all 16 versions.
54. Done: ledger version 3 (picks, their events, the monthly choices, records, the market line, facts and headlines),
    migrated from version 2 with every row kept.
55. Done: the evening pass runs the picks after the strategies, from the picks' own start date; a failure never stops
    the calls.
56. Done: company facts from stockanalysis.com (read again after a week) and headlines from Arab Finance's company news,
    only for the evening's shown picks.
57. Done: Today's "Technical picks" section, the pick page with sharing, the Learn page under Strategies, Settings
    (amount per pick, each list on or off), notifications (picks ready; a target or a close under the stop), the
    analyst's `picks` and `headlines` tools.
58. Done: 88 Arabic rows (to check).
59. Stop: ask the owner before installing on the Fold 7.

## Sessions that can run side by side

| Session | Items | Touches |
|---|---|---|
| Lead (branch `egx`, this checkout) | 1 to 13, then 22 to 29, 31 to 37, 39 to 46 | the engine, the app, the docs |
| B, its own worktree | 14 to 21 | only new files under `app/.../data/egx/` and their tests |
| C, design only | 30 | no repository files; mockups on the canvas |
| D, its own worktree | 38 | only new language and name tables |

B, C and D start as soon as the list is approved. The lead merges B and D into `egx` when each reports its tests
green, and builds items 31 to 34 only from screens the owner accepted in C.
