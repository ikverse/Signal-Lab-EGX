<!-- Proposal drafted 2026-11-14 at the owner's request ("Yes, draft it"). NOT approved: nothing here is built, and no
     source file has been changed. The research behind it was done the same day on free data (Yahoo prices, public news
     pages); its scripts are not in the repository yet (milestone E1 puts them there). -->

# Signal Lab EGX: daily calls for the Egyptian market

## What you asked, and what the research found

You asked how Signal Lab's idea (a few research-backed rules, daily calls, an honest paper ledger) can work on the
Egyptian market, with the last three or four years in front, and you trade on Thndr with the Trader plan. Six research
passes on 2026-11-14 ran the app's own engine and about 170 rule versions over 191 EGX stocks, 2010 to 2026. In plain
words:

- **The method carries over; most crypto rules do not.** The engine ran on EGX prices without a line changed. But the
  app's fast Breakout and its momentum rules did worse than nine in ten random picks since 2023.
- **Cash is the opponent.** A money-market fund paid about 20% a year. From 2015 to 2022 cash made 2.4 times your money
  and the 30 busiest stocks made nothing; from 2023 to 2026 cash made 2.0 and the stocks 3.8. A rule has to beat both.
- **The market changed.** Stocks trading EGP 10m a day went from 20 in 2022 to 99 in 2026. Selling quickly was the
  mistake in this market; slow rules that rarely sell kept up with it.
- **Four things held up** when tested the fair way (each year using only settings that had worked in earlier years):

| Rule | 2015–2022 | 2023–2026 | Note |
|---|---|---|---|
| Cash | 2.4 | 2.0 | |
| Holding the 30 busiest stocks | 1.0 | 3.8 | the real EGX30 made 3.5 in 2023–2026 |
| Dividend run | 3.0 | 3.0 | worst fall 4%; 14% of the money in stocks on average |
| Calm stocks | 3.6 | 5.6 | |
| Dividend payers | 9.2 | 3.8 | |
| Low-risk formula (published, untuned) | 3.0 | 5.5 | |
| Equal split of calm stocks, dividend payers and breakout | 7.9 | 3.9 | |
| Switching to whichever rule led the last three years | 2.5 | 1.4 | chasing fails |

- **When you place the order matters.** Over two years of hourly prices, stocks rose 0.36% on average from the close to
  the next open and fell 0.14% during the session. Buying the morning after a jump of 8% lost 0.9% against the market by
  the next day.
- **What failed:** collecting the dividend itself, buying dips, volume surges, sector strength, weekdays, Ramadan,
  holding overnight only, six-month momentum, and "market heat" as a warning.

Limits you should know: prices come from Yahoo's unofficial feed and needed cleaning; delisted stocks are missing (the
liquid ones were mostly bought out at a premium, so this probably does not flatter the results); some of what looks good
is luck after 170 tries. That is why every rule below starts on paper and has to keep earning its place.

## What gets built, in seven parts (the numbered build list is `docs/egx-build-list.md`)

### 1. A second app, not a second mode

Signal Lab EGX is its own app on your phone, built from this same repository and sharing the engine. It has its own
ledger in pounds, its own tiers and its own evening schedule. The crypto app is not touched: its ledger, its midnight
pass and its Binance data stay as they are. One app with a market switch would mix dollars and pounds in every screen
and in one paper account; two apps keep each honest.

### 2. Egyptian prices, cleaned

- Daily prices for every listed stock from Yahoo (free, no account), merged with its hourly prices, which carry the
  real opening price. The stock list and Yahoo names come from the table EGX Analyzer already uses.
- Cleaning, as in the research: a row with no volume is a day the stock did not trade; a one-day move beyond the
  exchange's 20% limit is a bonus issue or a bad row and is flattened; the fake "open" is never used.
- The stocks watched: the 80 busiest by 30-day turnover, listed at least 180 days, traded on 57 of the last 60 sessions.
- The EGX30 itself, built each day from its hourly prices and stored from the first run on, because no free source
  keeps its history.
- Upcoming dividends, read from Arab Finance's dividend news page and checked against each stock's dividend table; you
  can also add or correct one by hand.

### 3. Five strategies, three of them on Today

| Strategy | What it does | Starts as |
|---|---|---|
| **Dividend run** | When a general assembly fixes a cash dividend worth 4% or more of the price, buy, and sell on the last day that still carries the dividend. About 17 trades a year, mostly April to June | Promising: calls on Today |
| **Calm stocks** | At each month's end, hold the steadiest stocks among the 80 busiest that are above their 200-day average; sell the ones that drop off the list | Promising: calls on Today |
| **Dividend payers** | At each month's end, hold the stocks that paid the most in dividends over the last year | Promising: calls on Today |
| **Slow breakout** | Buy a new 100-day high, sell on a close below the 40-day low, no time limit, and never the day after a jump of 8% | Watch: recorded, no calls |
| **Low-risk formula** | Each quarter, from the calmer half of the stocks, hold those with the best twelve-month rise and dividend yield | Watch: recorded, no calls |

The crypto app's fast Breakout also runs silently as a control, so the ledger can show whether the research was right to
drop it. Every strategy and the 170 versions tried are counted, as the app already does, so luck is priced in.

### 4. A ledger that measures against cash and the market

- The paper account is in pounds. Money that is not in a stock earns the cash rate you set (19% to start).
- Every strategy is shown against three yardsticks: cash, the EGX30, and eight random stocks picked the same days. The
  third replaces "random entries" for the monthly lists.
- The paper account is split equally among the strategies that are on, and the split is fixed. A strategy is demoted
  only by its own record against the yardsticks, never because another did better lately.
- Costs: 0.16% a round trip in exchange fees and stamp duty (your Thndr plan waives commission on 50 orders a month),
  plus the spread: 0.5% in all for the 30 busiest stocks and 0.8% for the rest. Everything is also shown at double.
- A stock locked at its price limit cannot be bought or sold on paper either; the fill waits for the next day it trades.
- Dividends are paid into the paper account after the 5% tax.

### 5. Calls you can copy on Thndr

- The evening pass runs Sunday to Thursday after the close. Its notification says what to do tomorrow.
- Each call gives the amount in pounds and in whole shares, from "money I trade with" in Settings.
- Each call card shows four price levels (added 2026-11-14 at the owner's request), all rounded to the exchange's
  price step:
  - **Entry range**: from the last close down to half a normal day's move below it. Above the range the card says
    "wait", which is the don't-chase rule in numbers.
  - **Stop loss**: four normal days' moves below the entry. The alert fires on a close below it.
  - **Target 1** and **Target 2**: five and ten normal days' moves above the entry. At target 1 the call sells
    half; at target 2 it sells the rest. Whatever is still held leaves by the rule's own exit or the stop.
  - A dividend call keeps its date: it ends on the last day that carries the dividend.
- The levels were tested on 2026-11-14 on the research data (what 1.00 became, 2023-2026 then 2015-2022):
  - Selling at targets cost about half the gain in every strategy and both periods. Calm stocks: 5.95 and 5.12 by
    the rule; 2.95 and 1.84 with a stop at three moves and targets at two and four; 4.34 and 2.78 with a stop at
    four and targets at five and ten. Dividend payers, Dividend run and Slow breakout moved the same way.
  - A stop alone changed little (Calm stocks 5.72 and 4.50 at four moves; closer stops cost more before 2023).
  - The entry range alone changed almost nothing (Calm stocks 5.92 and 5.26) and made the falls slightly smaller.
- Decided by the owner on 2026-11-14, knowing this cost: the paper account sells at the targets. Five and ten moves
  are used because they cost the least of the target settings tested. The ledger also keeps a second record, "by the
  rule alone", so the live results show what the targets cost or saved.
- Each call says when to place it: buys near the close, sells at the open, and "wait a day" after a jump of 8%.
- Month-end brings one list: sell these, buy these, keep these.
- A dividend call carries its dates: the last day to hold, and when the cash arrives.
- A counter shows how many of the month's 50 free orders the calls have used.
- "I took it" and "My copies" work as in the crypto app.

### 6. Arabic and English (added 2026-11-14 at the owner's request)

- Settings has a language choice: Arabic, English, or follow the phone. Everything is translated: every screen, the
  calls, the evidence pages, the notifications, the Learn texts and the error messages.
- In Arabic the whole app reads right to left; charts and price tables keep their left-to-right direction.
- Each stock shows its Arabic company name beside its ticker, from a table of about 220 names kept in the app. Tickers
  stay in Latin letters, as Thndr shows them.
- Numbers and prices use the digits 0 to 9 in both languages unless you choose Arabic-Indic digits in Settings; dates
  follow the language.
- The wording is plain Egyptian market Arabic (سهم، كوبون، نهاية الحق، وقف خسارة), not a literal translation. You read
  the Arabic texts before the first install and correct anything that sounds wrong.
- Today the app's texts are written inside its code, in English only. For this they move into language tables in the
  parts both apps share, so the crypto app keeps working in English and could get Arabic later at little cost.

### 7. Design: Calm with cards (chosen 2026-11-14, replaced 2026-11-17)

Replaced by "Bright board" (mockup https://claude.ai/artifact/K9XwUp8835BSXzWxPKGtZ4): Paper and stone by day and a
near-black navy at night, an indigo-to-teal gradient on the one thing to press, the page names along the top as in EGX
Analyzer, EGX Analyzer's stock-sheet chart on the call, pick and stock pages, a price ladder drawn to scale, motion on
arrival, and a layout of its own for folded, unfolded and half-folded. What follows is the old design, for the record.

Accepted mockup: https://claude.ai/artifact/6qDLTnGJke5fcWiXXXjPhX

- **Feel.** Calm and plain: a neutral page, a lot of open space, large type, and a soft wash of mint and sky blue
  behind the top of the screen. No confetti, streaks or other game effects.
- **Today.** One plain sentence at the top says what to do tomorrow ("Tomorrow: buy two, sell one"), with the date
  and the market's state above it.
- **Calls.** Each call is a card you swipe through, softly tinted green to buy and red to sell. It shows the action,
  the company, the shares, the pounds and when to place the order, with a "placed the order" button.
- **Tiles.** Two tiles under the calls: the paper account against the EGX30 and cash, and the month's free orders.
- **Menu.** A floating pill at the bottom, within thumb reach: Today, Strategies, Ledger, Settings.
- **Fold 7.** Folded: sentence, call cards, tiles. Unfolded: the sentence and tiles on one side, all the call cards
  on the other. Half-folded: the sentence and tiles above the crease, the cards and the menu below it.
- **Colour.** Light: page #F3F6F4, cards white, text #0F1513, buy #0B8F63, sell #D6453D. Dark: page #0B0E0D with
  lighter cards #181C1B, text #EEF2F0, buy #3DD9A0, sell #FF7A72. Colour is used only for buy, sell and the wash.
- **Type.** Readex Pro for Arabic and English.
- **Still to draw** before E4: the call page, Strategies, Ledger and Settings, in the same style.

Left out of the first version: the futures data, the leverage and delisting vetoes, the Binance button, hourly
strategies and the AI analyst.

### 8. Technical picks (added 2026-11-16 at the owner's request; built on branch `technical`)

Channel-style recommendations, as the Telegram channels EGX Analyzer reads give them, from chart rules the research
tested first (`docs/egx-research.md` section 4). Up to five picks each evening, on paper, in their own record:

- **Short-term picks**, up to three: a new 20-day high on at least the 20-day average volume, in an uptrend (above the
  50 and 200-day averages), never the day after a jump of 8%. The card has the channel format: an entry range, target 1
  (half), target 2 (the rest) and a stop loss, all from the chart. Held up to 20 sessions.
- **Mid-term picks**, up to two: a tight 10-day range inside the full trend template. An entry range and a stop loss,
  **no targets** (the owner's choice on 2026-11-17: targets sold the winners too early); held until a close under the
  stop or the 50-day average, or 120 sessions.
- Each card also shows the shares for the owner's amount per pick, why it was picked, the list's record in the
  backtest and live, three company facts (price to earnings, profit or loss over twelve months, debt to equity, from
  stockanalysis.com), the company's latest headlines from Arab Finance with flags (results, general assembly, capital
  increase, dividend, a loss), and a "share as text" button for the owner's own notes.
- Today shows the market line (the EGX30 against its 50 and 200-day averages, and how many of the 80 busiest stocks are
  in an uptrend) above the two lists, then the picks still open. The market line is information only: as a filter it
  did not help.
- **Learning**: on the first evening of each month each list uses the version of its rule (8 each) that did best over
  all the history before that month. After 12 months live, a list behind both cash and the EGX30, or behind nine in ten
  random twins, is switched off: still recorded, no longer shown.
- The picks are never part of "money I trade with"; their paper account is eight slots of the amount per pick.

## Build order (branch `egx`; each milestone committed when its tests pass; never pushed unasked)

| # | Lands | You get |
|---|---|---|
| E1 | The research in `docs/egx-research.md` with its sources; the download and cleaning scripts in `tools/egx/`; the engine learns EGX's rules (245 trading days, cash interest, dividends, price limits, monthly lists that keep a stock while it stays ranked, dividend dates as a trigger); the whole-account test runs on EGX | The table above re-made by the app's own engine. If it disagrees with the research, work stops and you are told |
| E2 | Prices, the stock list, cleaning, the EGX30 and dividend dates on the phone | Nothing to see yet |
| E3 | The evening pass, the ledger in pounds, the three yardsticks, costs | A ledger that fills by itself |
| E4 | Today, the call page with amounts and timing, the month-end list, the evidence pages, the order counter | The app you would use |
| E5 | Every text moved into language tables; the Arabic translation; right-to-left layout; Arabic company names; the language choice in Settings | The app in Arabic or English |
| E6 | Installed on the Fold7 when you say so, after you have read the Arabic texts | Paper calls every evening |

Each milestone is built and unit-tested on the PC first, as before.

## What you decide

1. **"approve"** on this plan before any source changes. Then say whether E1 to E6 may run back to back without a new
   "approve" each.
2. ~~Two apps or one app with a market switch.~~ Decided 2026-11-14: two apps.
3. Which strategies start on Today. The plan proposes three; you can start with fewer.
4. The cash rate to start with (19%) and "money I trade with".
5. When to install on the phone.

## Not in this plan

Real orders from the app. Same-day trading, short selling and futures. Paid price data. Telegram recommendations (EGX
Analyzer stays its own app; its channels could be scored by this ledger later). Sharing calls with other people, which
needs a regulator's licence in Egypt.

## Questions the ledger will answer

- Do calm stocks keep beating eight random stocks on live prices, or was it the backtest?
- Does the dividend run still pay when the dates come from the news page, not from hindsight?
- Is buying near the close worth what the hourly prices say?
- Does the fast Breakout keep failing, and does the slow one earn a place on Today?
- Is 0.5% the real cost of a round trip in the stocks you actually trade?
- Do the technical picks beat random stocks on the same days with the same levels, live, as they did in the backtest?
- Does the learning step's month-by-month choice beat simply keeping the registered version?
