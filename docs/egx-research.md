# Signal Lab EGX: the research

What was tested on 2026-11-14 before `docs/egx-plan.md` was written, where the data came from, and the engine's
re-run of the results. The scripts are in `tools/egx/` (see its README); the data they made is kept, gitignored, in
`research/data/egx/`.

## 1. Data and sources

- **Prices:** Yahoo's public chart feed, `TICKER.CA` with `period1=0&interval=1d&events=div,splits`, no key; 191 usable
  stocks back to 2000 (`download.py`). Hourly bars for the 100 busiest, two years, by ISIN symbol (`hourly.py`).
- **Cleaning** (`prepare.py`): rows with no volume are days the stock did not trade; Yahoo's daily "open" is the
  previous close, not a trade, so it is never used (the tests fill at the session's typical price, or at the real open
  from the hourly bars in `fills.py`); about 155 one-day moves beyond 21% since 2019 are unadjusted bonus issues or bad
  rows (EGX caps a session at 20%) and are flattened. The market calendar is every day on which at least 20 stocks traded.
- **Missing:** delisted stocks (the liquid ones were mostly bought out at premiums of 13 to 48%, so their absence more
  likely understates results); EGX30 history beyond two years of hourly bars (a home-made index of the 30 busiest stands
  in; none tracked the real EGX30 closely, 10 to 16 points a year off, `proxy.py`).
- **Cash:** the IMF's monthly T-bill rate less 20% tax; 20% in 2025 and 19% after, where the series stops.
- **Dividends:** Yahoo's dividend tables. Dividend dates and amounts for live use come from Arab Finance's dividend news
  page (`arabfinance.com/ar/home/tagresult?slug=724`, server-rendered); per stock from stockanalysis.com.
- **Costs:** Thndr's Trader plan (EGP 245 a month, 50 commission-free orders): 0.03% fees and 0.05% stamp duty a side,
  0.16% a round trip before the spread. Tests used 0.5% a round trip, and 1.0% as a stress. No capital gains tax since
  July 2026; dividends taxed 5%.

## 2. The six passes

1. **The app's crypto rules, unchanged, 2020 to 2026:** Breakout about 3.0x, Trend timer 1.5x, the rest flat or down;
   real EGX30 about 3.7x, T-bills after tax 2.7x. Breakout was still behind random entries (t about -3.3).
2. **The last three to four years:** the market deepened (stocks trading EGP 10m a day: 20 in 2022, 99 in 2026). In
   2023 to 2026 nothing beat holding the market (EGX30 3.5x, cash 2.0x); rules that sell quickly gave up half the gain;
   slow breakouts with no time limit made 3.25 to 3.7x. About 21 versions tried.
3. **Egyptian ideas:** the calmest stocks in an uptrend, the top dividend payers, the run-up to dividend dates (stocks
   beat the market by 3.5 to 4% in the 20 sessions before the ex-date; capturing the dividend itself earns nothing), and
   not chasing (buying the day after an 8% jump lost 0.9% against the market the next day, 1,516 cases, t -7). Failed:
   bonus-share run-ups, volume surges, weekdays, Ramadan, the month's turn.
4. **Checking the third pass:** the calm-stocks edge was overstated across 96 settings; top dividend payers were the
   steadiest across data cuts. From hourly bars: prices rose 0.36% on average from the close to the next open and fell
   0.14% during the session, so buy late in the session and sell at the open. Failed: sector strength, market heat,
   small-minus-big; the fast Breakout was the worst rule since 2023 on every cut.
5. **Free data only:** flattening every unexplained gap-down (2,222 days, `optimistic.py`) barely moved any result. The
   dividend notice trade (buy 8 to 10 sessions before the ex-date, sell the day before) earned 1.4 to 2.9% over the
   market after costs (t 2.5 to 3.4). Overnight-only trading does not pay (`overnight.py`).
6. **The fair test (walk-forward, `wf.py`):** each year uses only the settings that had worked in the earlier years. The
   table in section 3. Dividend timing from 30 real 2025-26 announcements: the amount is final at the general assembly
   a median 16.5 sessions before the last day that carries it (`divacct.py`). Real opening prices moved the monthly
   rules' results by under 3%.

**The levels test** (`levels.py`): selling at targets cost about half the gain in every rule and both periods; a stop
alone changed little; the entry range alone changed almost nothing. The owner chose the range, a stop at four moves and
targets at five and ten, knowing the cost, and a second record "by the rule alone".

About 170 rule versions were tried in all. That count is the multiple-testing denominator (`EgxStrategies.RESEARCH_VERSIONS`).

## 3. The engine's re-run (build item 12)

`gradlew :research:run --args="egx"` runs the engine's own Egyptian rules over the same data (after
`tools/egx/export.py`), at 0.5% a round trip. What 1.00 became:

| | 2015-22 | 2023-26 | research |
|---|---|---|---|
| Cash | 2.41 | 1.98 | 2.41, 1.98 |
| Holding the 30 busiest | 0.99 | 3.76 | 0.99, 3.76 |
| Calm stocks, settings from the past only (96) | 3.63 | 5.63 | 3.63, 5.63 |
| Breakout, settings from the past only (20) | 10.95 | 2.33 | 10.95, 2.33 |
| Dividend payers, settings from the past only (16) | 9.21 | 3.79 | 9.21, 3.79 |
| Momentum, settings from the past only (18) | 2.77 | 1.89 | 2.77, 1.89 |
| Equal split of calm, dividend payers, breakout | 7.94 | 3.93 | 7.94, 3.93 |
| Switching to the last three years' leader | 2.51 | 1.44 | 2.51, 1.44 |
| Low-risk formula, 10 stocks | 3.00 | 5.49 | 3.00, 5.49 |
| Dividend run (from the assembly, 4%+) | 3.02 | 2.99 | 3.02, 2.99 |
| Calm stocks, by the rule alone / with the levels | 5.12 / 2.78 | 5.95 / 4.34 | same |
| Dividend payers, by the rule alone / with the levels | 7.06 / 3.60 | 3.59 / 2.74 | same |
| Dividend run, by the rule alone / with the levels | 3.02 / 2.96 | 2.99 / 2.85 | same |
| Slow breakout as registered (never after an 8% jump) | 7.76 | 5.15 | no like-for-like figure |

Every comparable line matches the research within 0.5%, the research's own rounding. Two things had to be right for
that: the month's end is known from the exchange's published holidays (guessing from weekdays alone put 24 of 201 month
ends a session late and moved results by up to 50%), and quarterly re-picks follow the next session's month, as tested.

**The engine as registered** lets a buy blocked by a price-limit lock or a session without trades wait up to five
sessions (build item 5); the research dropped such a buy. With that (`--args="egx wait"`), 2023-26 is unchanged on every
line; 2015-22 moves by up to 16% where settings are chosen year by year (a small change flips a year's pick: calm
stocks 3.06, dividend payers 8.03) and by under 3% for fixed settings, except the slow breakout with levels (3.15
against 3.44).

## 4. Limits

The prices are an unofficial feed that needed cleaning; delisted stocks are missing; some of what looks good is luck
after about 170 tries; the EGX30 yardstick of the backtest is a stand-in. That is why every rule starts on paper and has
to keep earning its place against cash, the EGX30 and random picks.
