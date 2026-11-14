"""Is holding EGX stocks only overnight (bought at the close, sold at the next open) worth it after fees?
Two years of hourly bars, 100 busiest stocks; fees 0.16% a round trip (Thndr Trader: exchange fees and stamp duty only)."""
import json, os, sys, statistics, collections, datetime, math
src, FEE = sys.argv[1], 0.0016
days = {}
for fn in sorted(os.listdir(src)):
    try: r = json.load(open(os.path.join(src, fn)))["chart"]["result"][0]
    except Exception: continue
    q = r["indicators"]["quote"][0]; d = collections.defaultdict(list)
    for i, t in enumerate(r.get("timestamp") or []):
        if q["close"][i] and q["open"][i] and q["volume"][i]:
            d[datetime.datetime.fromtimestamp(t + 7200, datetime.UTC).date()].append((q["open"][i], q["high"][i], q["low"][i], q["close"][i], q["volume"][i]))
    if len(d) > 200: days[fn[:-5]] = dict(d)
turn = {c: statistics.median(sum(b[4] * b[3] for b in bars) for bars in d.values()) for c, d in days.items()}
price = {c: statistics.median(bars[-1][3] for bars in d.values()) for c, d in days.items()}
order = sorted(turn, key=turn.get, reverse=True)
rank = {c: i for i, c in enumerate(order)}
print("ten busiest:", ", ".join(f"{c} (EGP {turn[c] / 1e6:.0f}m a day, price {price[c]:.1f})" for c in order[:10]))

rows = []   # (date of the open, code, overnight, session before, close position in the day's range, prior day's move, next session)
for code, d in days.items():
    ds = sorted(d)
    for i in range(1, len(ds) - 1):
        p, a, b = ds[i - 1], ds[i], ds[i + 1]
        if (b - a).days > 5 or (a - p).days > 5 or len(d[a]) < 3 or len(d[b]) < 3: continue
        c0, o1 = d[a][-1][3], d[b][0][0]
        night = o1 / c0 - 1
        if abs(night) > 0.12: continue
        hi, lo = max(x[1] for x in d[a]), min(x[2] for x in d[a])
        rows.append((b, code, night, c0 / d[a][0][0] - 1, (c0 - lo) / (hi - lo) if hi > lo else 0.5, c0 / d[p][-1][3] - 1, d[b][-1][3] / o1 - 1))

def show(label, sel, extra=0.0):
    if len(sel) < 30: print(f"  {label:<52} too few"); return
    byday = collections.defaultdict(list)
    for r in sel: byday[r[0]].append(r[2] - FEE - extra)
    dm = [statistics.mean(v) for v in byday.values()]
    m, sd = statistics.mean(dm), statistics.stdev(dm)
    allv = [r[2] for r in sel]
    print(f"  {label:<52} {len(sel):6d} nights  before fees {100 * statistics.mean(allv):+.2f}%  after fees {100 * (statistics.mean(allv) - FEE - extra):+.2f}%  up {100 * sum(v > 0 for v in allv) / len(allv):2.0f}%  t {m / (sd / math.sqrt(len(dm))):+5.1f}  nights used {len(dm)}")

print("ALL NIGHTS")
show("100 busiest stocks", rows)
show("ten busiest", [r for r in rows if rank[r[1]] < 10])
show("ranks 11 to 30", [r for r in rows if 10 <= rank[r[1]] < 30])
show("ranks 31 to 100", [r for r in rows if rank[r[1]] >= 30])
show("ten busiest, plus one price step each side", [r for r in rows if rank[r[1]] < 10], extra=statistics.mean(2 * (0.01 if price[c] >= 2 else 0.001) / price[c] for c in order[:10]))
for half in sorted({(r[0].year, 1 if r[0].month <= 6 else 2) for r in rows}):
    show(f"ten busiest, {half[0]} half {half[1]}", [r for r in rows if rank[r[1]] < 10 and (r[0].year, 1 if r[0].month <= 6 else 2) == half])
print("BY THE DAY BEFORE (30 busiest)")
top = [r for r in rows if rank[r[1]] < 30]
show("after a session that rose", [r for r in top if r[3] > 0])
show("after a session that fell", [r for r in top if r[3] < 0])
show("closed in the top fifth of its day's range", [r for r in top if r[4] >= 0.8])
show("closed in the bottom fifth of its range", [r for r in top if r[4] <= 0.2])
show("day up 3% or more", [r for r in top if r[5] >= 0.03])
show("day down 3% or more", [r for r in top if r[5] <= -0.03])
for wd, nm in ((6, "Thursday close to Sunday open"), (0, "Sunday to Monday"), (1, "Monday to Tuesday"), (2, "Tuesday to Wednesday"), (3, "Wednesday to Thursday")):
    show(nm, [r for r in top if r[0].weekday() == wd])
print("EACH OF THE TEN BUSIEST")
for c in order[:10]:
    show(c, [r for r in rows if r[1] == c])

# the real EGX30
r = json.load(open("case30_1h.json"))["chart"]["result"][0]; q = r["indicators"]["quote"][0]; d = collections.defaultdict(list)
for i, t in enumerate(r["timestamp"]):
    if q["close"][i] and q["open"][i]: d[datetime.datetime.fromtimestamp(t + 7200, datetime.UTC).date()].append((q["open"][i], q["close"][i]))
ds = sorted(d); n = [d[b][0][0] / d[a][-1][1] - 1 for a, b in zip(ds, ds[1:])]; s = [d[b][-1][1] / d[b][0][0] - 1 for b in ds[1:]]
print(f"THE EGX30 INDEX ITSELF: {len(n)} days; overnight {100 * statistics.mean(n):+.3f}% a night (up {100 * sum(x > 0 for x in n) / len(n):.0f}%), session {100 * statistics.mean(s):+.3f}%; total overnight {math.prod(1 + x for x in n):.2f}x, total sessions {math.prod(1 + x for x in s):.2f}x")

# accounts: one stock a night (two orders a day, inside 50 free orders a month)
def account(pick, label, fee=FEE):
    byday = collections.defaultdict(list)
    for r_ in rows: byday[r_[0]].append(r_)
    eq, peak, worst, used = 1.0, 1.0, 0.0, 0
    for day in sorted(byday):
        c = pick(byday[day])
        if c is None: continue
        eq *= 1 + c[2] - fee; used += 1
        peak = max(peak, eq); worst = min(worst, eq / peak - 1)
    print(f"  {label:<58} ends {eq:5.2f}x  worst fall {100 * worst:4.0f}%  nights {used}")
print("ACCOUNTS, one stock a night, whole account, nothing earned during the day (two years)")
for c in order[:5]:
    account(lambda cands, c=c: next((x for x in cands if x[1] == c), None), f"always {c}")
account(lambda cands: max((x for x in cands if rank[x[1]] < 10), key=lambda x: x[4], default=None), "of the ten busiest, the one that closed highest in its range")
account(lambda cands: max((x for x in cands if rank[x[1]] < 10), key=lambda x: x[5], default=None), "of the ten busiest, the day's biggest riser")
account(lambda cands: min((x for x in cands if rank[x[1]] < 10), key=lambda x: x[5], default=None), "of the ten busiest, the day's biggest faller")
account(lambda cands: min((x for x in cands if rank[x[1]] < 10), key=lambda x: rank[x[1]], default=None), "the busiest stock that traded")
hold = {c: days[c][sorted(days[c])[-1]][-1][3] / days[c][sorted(days[c])[0]][0][0] for c in order[:5]}
print("  for comparison, simply holding (raw prices, bonus issues not adjusted):", ", ".join(f"{c} {v:.2f}x" for c, v in hold.items()))
