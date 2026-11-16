# EGX research scripts

The scripts behind `docs/egx-plan.md` and `docs/egx-research.md`, copied unchanged on 2026-11-14 from the research
session's temporary folder. Python 3, standard library only. Run each with `python -I`.

A copy of the data they produced (raw Yahoo JSON, hourly bars, prepared candles) sits in `research/data/egx/`,
which is gitignored like the rest of `research/data/`.

| Script | Job |
|---|---|
| `download.py` | Daily history of every EGX stock from Yahoo (legacy `TICKER.CA` symbols) |
| `hourly.py` | Two years of hourly bars for the 100 busiest stocks (ISIN symbols) |
| `prepare.py` | Yahoo JSON to the candle files the whole-account test reads; the market calendar; the stand-in index |
| `optimistic.py` | A copy of the candles with unexplained gap-downs treated as unrecorded splits (an upper bound) |
| `proxy.py` | Which home-made index tracks the real EGX30 best |
| `fills.py` | Candles whose open is the real next-session price, from the hourly bars |
| `adapt3.py`, `harden.py`, `wf.py`, `divacct.py`, `levels.py` | The rule versions, walk-forward test, dividend account and the four price levels |
| `events.py`, `divwin.py` | Event studies: dividends, bonus shares, strong closes, volume surges, the calendar |
| `overnight.py`, `hourly_study.py` | When in the day to place orders; holding overnight only |
| `technical.py` | Technical picks: short and mid-term chart setups as whole accounts with chart levels, walk-forward, against random stocks |

Typical order: `download.py`, `hourly.py`, `prepare.py`, `fills.py`, then the studies.
