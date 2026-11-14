# Signal Lab EGX: changes

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
