"""Which home-made market index tracks the real EGX30 best? Compared with the EGX30's known yearly returns and its last two years of hourly bars."""
import datetime, json, os, sys
data = sys.argv[1]
DAY = 86_400_000
def read(p): return [l.split(",") for l in open(p) if l.strip()]
cal = [int(r[0]) for r in read(os.path.join(data, "d1", "BTCUSDT.csv"))]
N = len(cal); pos = {t: k for k, t in enumerate(cal)}
dates = [datetime.datetime.fromtimestamp(t / 1000, datetime.UTC).date() for t in cal]
C, Q, first = {}, {}, {}
for name in os.listdir(os.path.join(data, "d1")):
    code = name[:-4]
    if code == "BTCUSDT": continue
    rows = read(os.path.join(data, "d1", name))
    c, q = [None] * N, [0.0] * N
    for r in rows:
        k = pos[int(r[0])]; c[k] = float(r[4]); q[k] = float(r[7])
    C[code], Q[code], first[code] = c, q, pos[int(rows[0][0])]
def cum(q):
    out, s = [0.0] * (N + 1), 0.0
    for k in range(N): s += q[k]; out[k + 1] = s
    return out
CQ = {c: cum(Q[c]) for c in C}
def build(window, months, weighted, n=30):
    level, members, out, last = 1000.0, {}, [], None
    for k in range(N):
        if members:
            r = sum(w * (C[c][k] / C[c][k - 1] - 1) for c, w in members.items() if C[c][k] and C[c][k - 1])
            level *= 1 + r
            if weighted:  # weights drift with prices between reviews
                tot = 0.0
                for c in members:
                    if C[c][k] and C[c][k - 1]: members[c] *= C[c][k] / C[c][k - 1]
                    tot += members[c]
                members = {c: w / tot for c, w in members.items()}
        d = dates[k]; tag = (d.year, (d.month - 1) // months)
        if tag != last:
            last = tag
            ok = sorted(((CQ[c][k + 1] - CQ[c][max(0, k + 1 - window)], c) for c in C if C[c][k] and cal[k] - cal[first[c]] >= 180 * DAY), reverse=True)[:n]
            ok = [(t, c) for t, c in ok if t > 0]
            tot = sum(t for t, _ in ok)
            members = {c: (min(t / tot, 1.0) if weighted else 1 / len(ok)) for t, c in ok} if ok else {}
            if weighted and members:  # cap one name at 15%, like the EGX30 Capped
                for _ in range(5):
                    over = {c for c, w in members.items() if w > 0.15}
                    if not over: break
                    rest = sum(w for c, w in members.items() if c not in over)
                    spare = 1 - 0.15 * len(over)
                    members = {c: (0.15 if c in over else w * spare / rest) for c, w in members.items()}
        out.append(level)
    return out
real = {2016: 76.2, 2017: 21.7, 2018: -13.2, 2019: 7.1, 2020: -22.3, 2021: 10.2, 2022: 22.2, 2023: 70.1, 2024: 19.5, 2025: 40.65}
variants = {"equal, busiest 30, monthly": (30, 1, False), "equal, 6-month turnover, twice a year": (126, 6, False),
            "turnover-weighted, monthly": (30, 1, True), "turnover-weighted, 6-month turnover, twice a year": (126, 6, True)}
series = {}
print(f"{'':52}" + " ".join(f"{y:>6}" for y in real) + "   mean gap  2010-now")
print(f"{'real EGX30':52}" + " ".join(f"{v:+6.0f}" for v in real.values()))
for name, (w, m, wt) in variants.items():
    s = build(w, m, wt); series[name] = s
    yr = {}
    for y in real:
        ks = [k for k in range(N) if dates[k].year == y]
        yr[y] = 100 * (s[ks[-1]] / s[ks[0] - 1] - 1)
    k0 = next(k for k in range(N) if dates[k].year >= 2010)
    print(f"{name:52}" + " ".join(f"{yr[y]:+6.0f}" for y in real) + f"   {sum(abs(yr[y] - real[y]) for y in real) / len(real):6.1f}   {s[-1] / s[k0 - 1]:5.2f}x")
# the real index's last two years, from hourly bars
r = json.load(open("case30_1h.json"))["chart"]["result"][0]
off = r["meta"].get("gmtoffset", 7200); day = {}
for t, c in zip(r["timestamp"], r["indicators"]["quote"][0]["close"]):
    if c: day[(t + off) // 86400 * DAY] = c
common = [t for t in sorted(day) if t in pos]
print(f"real EGX30 from hourly bars: {len(common)} days, {dates[pos[common[0]]]} to {dates[pos[common[-1]]]}: {day[common[-1]] / day[common[0]]:.2f}x")
for name, s in series.items():
    a = [day[t] / day[u] - 1 for u, t in zip(common, common[1:])]
    b = [s[pos[t]] / s[pos[u]] - 1 for u, t in zip(common, common[1:])]
    ma, mb = sum(a) / len(a), sum(b) / len(b)
    cov = sum((x - ma) * (y - mb) for x, y in zip(a, b)); va = sum((x - ma) ** 2 for x in a); vb = sum((y - mb) ** 2 for y in b)
    print(f"  {name:50} {s[pos[common[-1]]] / s[pos[common[0]]]:.2f}x  daily correlation {cov / (va * vb) ** 0.5:.2f}")
json.dump({"t": cal, "c": series["turnover-weighted, 6-month turnover, twice a year"]}, open("proxy_weighted.json", "w"))
json.dump({"t": cal, "c": series["equal, 6-month turnover, twice a year"]}, open("proxy_equal6.json", "w"))
