"""Event studies for EGX-native ideas: cash dividends, bonus shares, strong closes, volume surges, the calendar.

usage: python -I events.py <data dir (typical)> <raw dir> <cost per round trip>

Prices are the cleaned candles (open = the day's typical price). A trade decided at a close is bought at the next market
day's typical price and sold at a later day's typical price. "Over market" subtracts the stand-in index over the same days.
The t value is taken across calendar months, because events bunch together.
"""
import datetime, json, math, os, sys

data, raw, COST = sys.argv[1], sys.argv[2], float(sys.argv[3])
DAY = 86_400_000


def read(p):
    return [l.split(",") for l in open(p) if l.strip()]


cal = [int(r[0]) for r in read(os.path.join(data, "d1", "BTCUSDT.csv"))]
N = len(cal)
pos = {t: k for k, t in enumerate(cal)}
dates = [datetime.datetime.fromtimestamp(t / 1000, datetime.UTC).date() for t in cal]
idx = json.load(open("proxy_equal6.json"))["c"]

S = {}
for name in sorted(os.listdir(os.path.join(data, "d1"))):
    code = name[:-4]
    if code == "BTCUSDT":
        continue
    rows = read(os.path.join(data, "d1", name))
    st = {f: [None] * N for f in "ohlcq"}
    st["first"] = pos[int(rows[0][0])]
    for r in rows:
        k = pos[int(r[0])]
        st["o"][k], st["h"][k], st["l"][k], st["c"][k], st["q"][k] = float(r[1]), float(r[2]), float(r[3]), float(r[4]), float(r[7])
    acc, s = [0.0] * N, 0.0
    for k in range(N):
        s += st["q"][k] or 0.0
        if k >= 30:
            s -= st["q"][k - 30] or 0.0
        acc[k] = s
    st["t30"] = acc
    S[code] = st

_rank = {}


def liquid(code, k, n=80):
    """Was the stock among the n busiest at the close of day k (and listed 180 days)?"""
    if k not in _rank:
        ok = sorted(((st["t30"][k], c) for c, st in S.items() if st["c"][k] is not None and cal[k] - cal[st["first"]] >= 180 * DAY and st["t30"][k] > 0), reverse=True)
        _rank[k] = {c: i for i, (_, c) in enumerate(ok)}
    return _rank[k].get(code, 999) < n


def tradable(st, k, buy):
    if k >= N or st["c"][k] is None or not st["q"][k]:
        return False
    if st["h"][k] == st["l"][k] and st["c"][k - 1]:
        move = st["c"][k] / st["c"][k - 1] - 1
        if (buy and move >= 0.045) or (not buy and move <= -0.045):
            return False
    return True


def trade(st, k_buy, k_sell):
    """Bought at day k_buy's typical price, sold at k_sell's; None if either side could not be filled."""
    if k_sell >= N or not tradable(st, k_buy, True):
        return None
    while k_sell < N and not tradable(st, k_sell, False):
        k_sell += 1  # locked or not traded: wait for the next day it can be sold
    if k_sell >= N or st["o"][k_sell] is None:
        return None
    return st["o"][k_sell] / st["o"][k_buy] - 1, idx[k_sell] / idx[k_buy] - 1


def show(label, rows):
    """rows: (day index, value). Mean, median, share positive, t across months."""
    if len(rows) < 8:
        print(f"    {label:<46} {len(rows):5d} events: too few")
        return
    vals = sorted(v for _, v in rows)
    months = {}
    for k, v in rows:
        months.setdefault(dates[k].strftime("%Y-%m"), []).append(v)
    mm = [sum(v) / len(v) for v in months.values()]
    m = sum(mm) / len(mm)
    sd = math.sqrt(sum((x - m) ** 2 for x in mm) / (len(mm) - 1)) if len(mm) > 1 else 0
    t = m / (sd / math.sqrt(len(mm))) if sd else 0
    print(f"    {label:<46} {len(rows):5d} events  mean {100 * sum(vals) / len(vals):+6.2f}%  median {100 * vals[len(vals) // 2]:+6.2f}%  won {100 * sum(v > 0 for v in vals) / len(vals):3.0f}%  t {t:+5.2f}")


def periods(rows_by_label):
    for era, a, b in (("2015-2022", 2015, 2022), ("2023-2026", 2023, 2026)):
        print(f"  {era}")
        for label, rows in rows_by_label:
            show(label, [(k, v) for k, v in rows if a <= dates[k].year <= b])


# ---- events from Yahoo
divs, bonus = [], []
for name in sorted(os.listdir(raw)):
    code = name[:-5]
    if code not in S:
        continue
    try:
        r = json.load(open(os.path.join(raw, name)))["chart"]["result"][0]
    except Exception:
        continue
    off = r["meta"].get("gmtoffset", 7200)
    q = r["indicators"]["quote"][0]
    rawc = {}
    for i, t in enumerate(r.get("timestamp") or []):
        if q["close"][i] and q["volume"][i]:
            rawc[(t + off) // 86400 * DAY] = q["close"][i]
    ev = r.get("events") or {}
    for d in (ev.get("dividends") or {}).values():
        day = (d["date"] + off) // 86400 * DAY
        if day in pos and d.get("amount"):
            before = [rawc[t] for t in sorted(rawc) if t < day][-1:]
            if before:
                divs.append((code, pos[day], d["amount"] / before[0]))
    for sp in (ev.get("splits") or {}).values():
        day = (sp["date"] + off) // 86400 * DAY
        if day in pos and sp.get("numerator") and sp.get("denominator") and sp["numerator"] > sp["denominator"]:
            bonus.append((code, pos[day], sp["numerator"] / sp["denominator"] - 1))

print(f"cost {100 * COST:.1f}% a round trip; {len(divs)} cash dividends and {len(bonus)} bonus issues or splits in Yahoo's records\n")

# ---- 1. cash dividends
print("1. CASH DIVIDENDS (stocks among the 80 busiest; yield between 1% and 25%)")
ok = [(c, k, y) for c, k, y in divs if 0.01 <= y <= 0.25 and k >= 25 and k + 25 < N and liquid(c, k - 2)]
by_year = {}
for c, k, y in ok:
    by_year.setdefault(dates[k].year, []).append(y)
print("  events and median yield by year: " + "  ".join(f"{y}: {len(v)} at {100 * sorted(v)[len(v) // 2]:.1f}%" for y, v in sorted(by_year.items()) if y >= 2019))
month = {}
for c, k, y in ok:
    if dates[k].year >= 2023:
        month[dates[k].month] = month.get(dates[k].month, 0) + 1
print("  ex-dates by month, 2023-2026: " + " ".join(f"{m}:{n}" for m, n in sorted(month.items())))
drop, runup20, runup5, cap1, cap1x, cap5, hold20, after5, after20 = [], [], [], [], [], [], [], [], []
for c, k, y in ok:
    st = S[c]
    if None in (st["c"][k], st["c"][k - 1], st["c"][k - 21], st["c"][k - 6]):
        continue
    drop.append((k, -(st["c"][k] / st["c"][k - 1] - 1) / y))
    runup20.append((k, st["c"][k - 1] / st["c"][k - 21] - idx[k - 1] / idx[k - 21]))
    runup5.append((k, st["c"][k - 1] / st["c"][k - 6] - idx[k - 1] / idx[k - 6]))
    t = trade(st, k - 1, k)  # bought the day before the ex-date, sold on it
    if t:
        cap1.append((k, t[0] + 0.95 * y - COST))
        cap1x.append((k, t[0] + 0.95 * y - COST - t[1]))
    t = trade(st, k - 5, k + 1)
    if t:
        cap5.append((k, t[0] + 0.95 * y - COST - t[1]))
    t = trade(st, k - 20, k - 1)  # the run-up alone: out before the ex-date
    if t:
        hold20.append((k, t[0] - COST - t[1]))
    t = trade(st, k + 1, k + 6)
    if t:
        after5.append((k, t[0] - COST - t[1]))
    t = trade(st, k + 1, k + 21)
    if t:
        after20.append((k, t[0] - COST - t[1]))
periods([
    ("ex-day fall as a share of the dividend", drop),
    ("20 days before, over market (no cost)", runup20),
    ("5 days before, over market (no cost)", runup5),
    ("buy day before ex, sell ex-day, + dividend", cap1),
    ("  the same, over market", cap1x),
    ("buy 5 days before, sell day after, over market", cap5),
    ("buy 20 days before, sell before ex, over market", hold20),
    ("buy after ex, hold 5 days, over market", after5),
    ("buy after ex, hold 20 days, over market", after20),
])
for lo, hi in ((0.01, 0.04), (0.04, 0.08), (0.08, 0.25)):
    sel = {(c, k) for c, k, y in ok if lo <= y < hi}
    ks = {k for _, k in sel}
    show(f"  capture over market, yield {100 * lo:.0f}-{100 * hi:.0f}%, 2019 on", [(k, v) for (k, v), (c, kk, y) in zip(cap1x, [e for e in ok if None not in (S[e[0]]['c'][e[1]], S[e[0]]['c'][e[1] - 1], S[e[0]]['c'][e[1] - 21], S[e[0]]['c'][e[1] - 6])]) if lo <= y < hi and dates[k].year >= 2019] if False else [])

# ---- 2. bonus shares
print("\n2. BONUS SHARES AND SPLITS (the dates Yahoo knows; stocks among the 80 busiest)")
b20, b5, a5, a20 = [], [], [], []
for c, k, ratio in bonus:
    st = S[c]
    if k < 25 or k + 25 >= N or not liquid(c, k - 2) or None in (st["c"][k - 21], st["c"][k - 1]):
        continue
    t = trade(st, k - 20, k - 1)
    if t:
        b20.append((k, t[0] - COST - t[1]))
    t = trade(st, k - 5, k - 1)
    if t:
        b5.append((k, t[0] - COST - t[1]))
    t = trade(st, k + 1, k + 6)
    if t:
        a5.append((k, t[0] - COST - t[1]))
    t = trade(st, k + 1, k + 21)
    if t:
        a20.append((k, t[0] - COST - t[1]))
periods([("buy 20 days before, sell before ex, over market", b20), ("buy 5 days before, sell before ex, over market", b5),
         ("buy after ex, hold 5 days, over market", a5), ("buy after ex, hold 20 days, over market", a20)])

# ---- 3. strong closes and volume surges
print("\n3. AFTER A STRONG DAY (stocks among the 80 busiest; bought next day; over market, after cost)")
strong = {n: [] for n in (1, 3, 5, 10)}
locked = {n: [] for n in (1, 3, 5, 10)}
surge = {n: [] for n in (1, 3, 5, 10)}
weak = {n: [] for n in (1, 3, 5, 10)}
for c, st in S.items():
    for k in range(max(st["first"] + 35, 1), N - 12):
        if st["c"][k] is None or not st["q"][k] or not st["c"][k - 1] or dates[k].year < 2015 or not liquid(c, k - 1):
            continue
        move = st["c"][k] / st["c"][k - 1] - 1
        avgq = (st["t30"][k - 1]) / 30
        for n in (1, 3, 5, 10):
            if move >= 0.08 or move <= -0.08 or (avgq > 0 and st["q"][k] >= 3 * avgq and move >= 0.03):
                t = trade(st, k + 1, k + 1 + n)
                if not t:
                    continue
                v = (k, t[0] - COST - t[1])
                if move >= 0.08:
                    strong[n].append(v)
                    if st["h"][k] == st["l"][k] or st["c"][k] >= st["h"][k] * 0.999:
                        locked[n].append(v)
                if move <= -0.08:
                    weak[n].append(v)
                if avgq > 0 and st["q"][k] >= 3 * avgq and move >= 0.03:
                    surge[n].append(v)
periods([(f"up 8% or more, hold {n} days", strong[n]) for n in (1, 3, 5, 10)]
        + [(f"  closed at its high, hold {n} days", locked[n]) for n in (1, 5)]
        + [(f"turnover 3x normal and up 3%, hold {n} days", surge[n]) for n in (1, 3, 5, 10)]
        + [(f"down 8% or more, hold {n} days", weak[n]) for n in (1, 5)])

# ---- 4. the calendar (the stand-in index, no costs)
print("\n4. THE CALENDAR (stand-in index, daily change, no costs)")
RAMADAN = {2010: ("08-11", "09-09"), 2011: ("08-01", "08-29"), 2012: ("07-20", "08-18"), 2013: ("07-10", "08-07"), 2014: ("06-29", "07-27"),
           2015: ("06-18", "07-16"), 2016: ("06-06", "07-05"), 2017: ("05-27", "06-24"), 2018: ("05-17", "06-14"), 2019: ("05-06", "06-03"),
           2020: ("04-24", "05-23"), 2021: ("04-13", "05-12"), 2022: ("04-02", "05-01"), 2023: ("03-23", "04-20"), 2024: ("03-11", "04-09"),
           2025: ("03-01", "03-29"), 2026: ("02-19", "03-19")}


def in_ramadan(d):
    a, b = RAMADAN.get(d.year, ("13-01", "13-01"))
    return a <= d.strftime("%m-%d") <= b


for era, a, b in (("2015-2022", 2015, 2022), ("2023-2026", 2023, 2026)):
    ks = [k for k in range(1, N) if a <= dates[k].year <= b]
    r = {k: idx[k] / idx[k - 1] - 1 for k in ks}
    print(f"  {era}: average day {100 * sum(r.values()) / len(r):+.3f}%")
    for wd, nm in ((6, "Sunday"), (0, "Monday"), (1, "Tuesday"), (2, "Wednesday"), (3, "Thursday")):
        show(nm, [(k, r[k]) for k in ks if dates[k].weekday() == wd])
    turn = set()
    for k in ks:
        if k + 1 < N and dates[k + 1].month != dates[k].month:
            turn.update(j for j in (k, k + 1, k + 2, k + 3) if j in r)
    show("last day and first three of a month", [(k, r[k]) for k in ks if k in turn])
    show("all other days", [(k, r[k]) for k in ks if k not in turn])
    show("Ramadan", [(k, r[k]) for k in ks if in_ramadan(dates[k])])
    show("outside Ramadan", [(k, r[k]) for k in ks if not in_ramadan(dates[k])])
