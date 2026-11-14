"""When in the day is it cheapest to buy? Two years of hourly bars for the busiest EGX stocks (Yahoo ISIN symbols, raw prices)."""
import json, os, sys, statistics, collections, datetime
src = sys.argv[1]
days = {}   # code -> {date: [(hour, o, h, l, c, v)]}
for fn in sorted(os.listdir(src)):
    try: r = json.load(open(os.path.join(src, fn)))["chart"]["result"][0]
    except Exception: continue
    off = r["meta"].get("gmtoffset", 7200); q = r["indicators"]["quote"][0]; d = collections.defaultdict(list)
    for i, t in enumerate(r.get("timestamp") or []):
        if q["close"][i] and q["open"][i] and q["volume"][i]:
            lt = datetime.datetime.fromtimestamp(t + off, datetime.UTC)
            d[lt.date()].append((lt.hour + lt.minute / 60, q["open"][i], q["high"][i], q["low"][i], q["close"][i], q["volume"][i]))
    if len(d) > 200: days[fn[:-5]] = dict(d)
print(len(days), "stocks with at least 200 days of hourly bars")
hours = collections.Counter(round(b[0], 1) for d in days.values() for bars in d.values() for b in bars)
print("bars by Cairo hour:", sorted(hours.items()))
night, intra, gapabs, first, last = [], [], [], [], []
path = collections.defaultdict(list)       # hour -> bar close relative to the day's first price
after_jump = collections.defaultdict(list)
norm_gap = []
for code, d in days.items():
    ds = sorted(d)
    for a, b in zip(ds, ds[1:]):
        if (b - a).days > 5: continue
        pa, pb = d[a], d[b]
        if len(pb) < 3 or len(pa) < 3: continue
        c0, o1, c1 = pa[-1][4], pb[0][1], pb[-1][4]
        gap = o1 / c0 - 1
        if abs(gap) > 0.12: continue           # a corporate action or a bad row
        night.append(gap); intra.append(c1 / o1 - 1); gapabs.append(abs(gap))
        first.append(pb[0][4] / o1 - 1); last.append(c1 / pb[-1][1] - 1)
        for bar in pb: path[int(bar[0])].append(bar[4] / o1 - 1)
        move0 = c0 / pa[0][1] - 1
        prev = ds[ds.index(a) - 1] if ds.index(a) else None
        day_move = c0 / d[prev][-1][4] - 1 if prev and (a - prev).days <= 5 else None
        if day_move is not None and abs(day_move) <= 0.21:
            key = "after a day up 8% or more" if day_move >= 0.08 else ("after a day down 8% or more" if day_move <= -0.08 else "after an ordinary day")
            after_jump[key].append((gap, c1 / o1 - 1, min(x[3] for x in pb) / o1 - 1, statistics.mean((x[2] + x[3] + x[4]) / 3 for x in pb) / o1 - 1))
pct = lambda v: f"{100 * statistics.mean(v):+.3f}%"
n = len(night)
print(f"{n} stock-days")
print(f"overnight (last close to the first trade): mean {pct(night)}, up on {100 * sum(x > 0 for x in night) / n:.0f}% of days, typical size {100 * statistics.median(gapabs):.2f}%")
print(f"during the session (first trade to close):  mean {pct(intra)}, up on {100 * sum(x > 0 for x in intra) / n:.0f}% of days")
print(f"first hour alone {pct(first)}; last bar alone {pct(last)}")
print("average price through the day, against the first trade:")
for h in sorted(path):
    if len(path[h]) > n * 0.3: print(f"   by the end of the {h}:00 bar: {pct(path[h])}  (lower than the open on {100 * sum(x < 0 for x in path[h]) / len(path[h]):.0f}% of days)")
for key, v in after_jump.items():
    print(f"{key}: {len(v)} cases; next day opens {pct([x[0] for x in v])}, then open to close {pct([x[1] for x in v])}, day's low {pct([x[2] for x in v])} under the open, average price of the day {pct([x[3] for x in v])} against the open")
