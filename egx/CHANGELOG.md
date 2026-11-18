# Signal Lab EGX: changes

## 0.4.1

A fix. Every call is still on paper, and nothing here is financial advice.

- **Ledger and Settings no longer crash.** Swiping or tapping to either page closed the app since 0.4.0; the
  pounds/dollars/gold, targets and language switches now draw. Opening a call's or a pick's chart no longer crashes either.

Not verified yet: not tried on the phone.

## 0.4.0

A new look, and Arabic in standard Arabic. Every call is still on paper, and nothing here is financial advice.

- **A new look.** A warm stone page by day and a near-black navy at night, with one indigo-to-teal button on each
  screen. Each strategy has its own colour and mark wherever it appears. Cards arrive with a little motion, figures count
  up and lines draw themselves in; all of it stays still when Android's animations are off.
- **The pages along the top.** Today, Strategies, Ledger, Analyst and Settings are now a row of names at the top: the
  page you are on in large letters. Swipe or tap to change page.
- **Made for the Fold.** Folded, unfolded and half-folded each get their own layout. Unfolded, Today shows what to do
  tomorrow beside how things stand. Half-folded, the upper half is for reading and the lower half for tapping.
- **Charts on every call.** A call or a pick now shows the stock's chart with its levels drawn across it and every price in
  a column on the right. The four levels sit on a ladder drawn to scale, so the gain to the target and the loss to the
  stop show as lengths, with the reward against the risk in one figure.
- **The order of the day.** On the open screen, Today lists what to sell at the open and what to buy near the close.
- **Standard Arabic.** Every Arabic text was rewritten from Egyptian into Modern Standard Arabic. Dividends are now
  التوزيعات, cash is النقد, and amounts are written ج.م.
- **Also new:**
  - The strategy cards each show a small line of their record.
  - The ledger's bars slide when you switch between pounds, dollars and gold.
  - The home-screen widget has the new colours.

Not verified yet: not tried on the phone, and the Arabic has not been read by the owner.

## 0.3.0

Technical picks. Every pick is on paper, and nothing here is financial advice.

- **Technical picks each evening.** Up to five picks from chart rules tested on the market since 2010:
  - **Short term**, up to three: an entry range, target 1 (sell half), target 2 (sell the rest) and a stop loss, held up
    to 20 sessions.
  - **Mid term**, up to two: an entry range and a stop loss, with no targets. Held up to 120 sessions, or until a close
    under the stop or under the 50-day average.
- **Each pick's page** shows:
  - the shares for your amount per pick;
  - why it was picked;
  - the list's record in the backtest and live;
  - the company's price to earnings, profit or loss, and debt to equity;
  - recent headlines, with flags for results, general assembly, capital increase, dividend or a loss;
  - a "share as text" button.
- **On Today**, the market line (the EGX30 against its 50 and 200-day averages, and how many of the busiest stocks are
  rising) sits above the picks, followed by the picks still open.
- **Learning each month.** Each list switches to the version of its rule that did best on the history before that
  month. After 12 months live, a list that falls behind cash and the EGX30, or behind random picks, is switched off.
- **Elsewhere:**
  - Settings: the amount per pick, and an on/off switch for each list.
  - Notifications: when picks are ready, and when a pick reaches a target or closes under its stop.
  - Strategies: a Learn page explaining each list.
  - The AI analyst: tools for the picks and for a stock's headlines.

Not verified yet: not tried on the phone; the 88 new Arabic texts have not been read by the owner.

## 0.2.3

- **Updates work when GitHub is busy.** When GitHub limits requests from your network, "Check for updates" now asks
  GitHub's release page instead of stopping with an error. An update found that way shows without its notes. If both
  fail, the message says when to try again.

Not verified yet: not tried on the phone.

## 0.2.2

- **The QR scan works.** In 0.2.1, "Scan ClaudeIn's QR code" stopped with an error before the camera opened. Fixed.

Not verified yet: not tried on the phone or against the real ClaudeIn.

## 0.2.1

- **Pair by scanning.** On the Analyst page, "Scan ClaudeIn's QR code" pairs the phone with ClaudeIn on your PC in one
  step: no address or code to type and no fingerprint to compare. Typing the address and code still works.

Not verified yet: not tried on the phone or against the real ClaudeIn; the two new Arabic texts have not been read by
the owner.

## 0.2.0

Five new things. Every call is still on paper, and nothing here is financial advice.

- **A page for each stock.** Tap a company on a call, on the month-end list or in My copies to see its price chart (3
  months, a year, or all), with the entry range, stop and targets drawn on it while a strategy holds it, its dividends,
  and every paper trade the strategies made on it, after costs.
- **A dividend calendar**, from Today and from each stock's page: a month at a glance with each dividend's last day to
  hold and its cash date, the amount, its yield at the last close, and whether the Dividend run holds it. "Add to my
  calendar" opens your phone's calendar with the date filled in.
- **The Ledger in dollars and gold.** Choose pounds, dollars or gold: the account, cash and the EGX30 are counted in it,
  beside what holding dollars or gold would have made, so a rise that is only the pound falling shows as such. The
  dollar is the official rate, not the parallel market's.
- **A home-screen widget** with tomorrow's plan, the count of buys and sells, and the paper account against the EGX30.
  It updates after each evening pass.
- **The analyst.** A new Analyst page: pair the phone with the analyst host on your PC, then ask about your strategies,
  calls, stocks and dividends. Claude answers on your PC from this phone's own numbers, and can look up the news. It only
  reads; it cannot place, change or delete anything.

Not verified yet: none of these has been tried on the phone; the Arabic texts added for them have not been read by the
owner; the analyst has been tested against a stand-in for the PC host, not the real one.

## 0.1.1

Fixes from the first look on the Fold 7.

- The page now fills the whole screen: no white strip behind the status bar and no grey strip behind the gesture bar.
- Titles no longer break in the middle of a word, and text is smaller throughout.
- The wash at the top fades out smoothly; the menu at the bottom no longer shows the page through it.
- Switches and text fields use the app's own colours.
- Before the first evening pass, the pages say the record has not started yet, without empty bars and dashes.
- In Arabic, numbers keep their order ("4%", "8 أسهم عشوائية", "2015–2022").
- Today names the next session day, never a Friday or a Saturday.

## 0.1.0

The first version of Signal Lab EGX: evening paper calls for the Egyptian Exchange, copied by hand on Thndr. A research
tool: every call is on paper, the app never trades, and nothing here is financial advice.

- **Five strategies from the research** (`docs/egx-research.md`): Dividend run, Calm stocks and Dividend payers give
  calls on Today; Slow breakout and the Low-risk formula are recorded on Watch; the crypto app's fast Breakout runs
  silently as a control. The app's engine re-makes the research's results within 0.5%.
- **An evening pass**, Sunday to Thursday at 15:45 Cairo time, with retries until the day's prices are out: Yahoo's
  prices cleaned as in the research, the EGX30, and dividend dates from Arab Finance's news.
- **Calls you can copy:** the amount in pounds and whole shares from "money I trade with", when to place the order, the
  four levels (entry range, stop at four moves, targets at five and ten), dividend dates, and the month-end list.
- **An honest ledger in pounds:** the paper account split equally among the strategies that are on, idle money earning
  the cash rate (19% to start), dividends after the 5% tax, costs of 0.5% to 0.8% a round trip and also at double, two
  records (with the targets and by the rule alone), and three yardsticks (cash, the EGX30, random stocks picked the same
  days). "I took it" keeps your own copies.
- **Arabic and English**, right to left in Arabic, Arabic company names, Arabic-Indic digits if you want them.
- **The "Calm with cards" design** for the Fold 7, folded, unfolded and half-folded.
- **Updates from this repository's releases**, checked once a day; Android's installer always asks first.

Not verified yet: the Arabic texts and company names have not been read by the owner; "Copy amount and open Thndr" has
not been tried on a phone (Thndr's app id is not published); the order price step is taken as 0.01 pounds.
