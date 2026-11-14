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


print("CASH DIVIDENDS: buying a set number of sessions before the ex-date and selling the day before it (over market, after cost)")
ok = [(c, k, y) for c, k, y in divs if 0.01 <= y <= 0.25 and k >= 45 and k + 25 < N and liquid(c, k - 2)]
res = {}
for lead in (40, 30, 20, 15, 10, 8, 5, 3):
    for name, lo in (("all", 0.01), ("yield 4%+", 0.04), ("yield 6%+", 0.06)):
        rows = []
        for c, k, y in ok:
            if y < lo:
                continue
            t = trade(S[c], k - lead, k - 1)
            if t:
                rows.append((k, t[0] - COST - t[1]))
        res[(lead, name)] = rows
for era, a, b in (("2015-2022", 2015, 2022), ("2023-2026", 2023, 2026)):
    print(f"  {era}")
    for (lead, name), rows in res.items():
        show(f"{lead} sessions before, {name}", [(k, v) for k, v in rows if a <= dates[k].year <= b])
# absolute (not over market) and the share of the run-up that comes in each stretch, 2023-26
print("  where the run-up happens, 2023-2026, yield 4%+ (stock minus market, no cost):")
for a_, b_ in ((40, 30), (30, 20), (20, 10), (10, 5), (5, 1)):
    v = []
    for c, k, y in ok:
        st = S[c]
        if y >= 0.04 and dates[k].year >= 2023 and st["c"][k - a_] and st["c"][k - b_]:
            v.append(st["c"][k - b_] / st["c"][k - a_] - idx[k - b_] / idx[k - a_])
    print(f"    from {a_} to {b_} sessions before: {100 * sum(v) / len(v):+.2f}% on average, middle {100 * sorted(v)[len(v) // 2]:+.2f}%, {len(v)} cases")
