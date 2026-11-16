"""Technical picks, stage 1 of the technical-picks plan: the short-term and mid-term chart setups followed as whole paper
accounts with chart levels, against cash, the market and random stocks, and chosen walk-forward.

usage: python -I technical.py <egx research dir> [x2]

The dir holds data_typical/ (from prepare.py), raw/ (Yahoo JSON, for dividends), dbn2.json (the IMF T-bill series) and
proxy_equal6.json (the stand-in index). "x2" doubles every cost.

Conventions are levels.py's: a call decided at a close is bought on a later session at that session's typical price (the
`open` column), never on a session locked at its price limit; idle money earns the T-bill rate after tax; dividends are
paid in after 5% tax; costs are 0.5% a round trip for the 30 busiest stocks and 0.8% for the rest.

The levels of a pick are fixed in pounds at the close it is decided on, as the card shows them:
  entry range  from max(breakout level, close - 0.5 ATR) up to the close; the order stays three sessions; a session that
               opens above the range fills at its top if its low comes back to it, else waits
  stop loss    under the lowest low of the last 10 sessions, at least 1.5 ATR and at most 4 ATR under the close; a close
               below it sells at the next session
  target 1     the first earlier swing high (a high that tops the five sessions on each side, last 250 sessions) at least
               twice the risk above the close, else twice the risk; half is sold there
  target 2     the next swing high at least one risk above target 1, else four times the risk; the rest is sold there
  time limit   a pick still held after its limit sells at the next session
"""
import bisect, datetime, json, math, os, random, statistics, sys

root = sys.argv[1]
FACTOR = 2.0 if "x2" in sys.argv[2:] else 1.0
data = os.path.join(root, "data_typical")
DAY = 86_400_000
YEAR = 245.0


def read(path):
    return [line.split(",") for line in open(path) if line.strip()]


cal = [int(r[0]) for r in read(os.path.join(data, "d1", "BTCUSDT.csv"))]
N = len(cal)
pos = {t: k for k, t in enumerate(cal)}
dates = [datetime.datetime.fromtimestamp(t / 1000, datetime.UTC).date() for t in cal]
idx = json.load(open(os.path.join(root, "proxy_equal6.json")))["c"]


def sma(x, n):
    out, s, cnt = [None] * N, 0.0, 0
    for k in range(N):
        if x[k] is not None:
            s += x[k]; cnt += 1
        if k >= n and x[k - n] is not None:
            s -= x[k - n]; cnt -= 1
        if k >= n - 1 and cnt == n:
            out[k] = s / n
    return out


def prior_extreme(x, n, big):
    """max (big) or min of x[k-n..k-1], when at least 80% of those sessions have a value; a monotonic queue."""
    from collections import deque
    out, q, cnt = [None] * N, deque(), 0
    for k in range(N):
        if k >= 1:
            j = k - 1
            if x[j] is not None:
                cnt += 1
                while q and ((x[q[-1]] <= x[j]) if big else (x[q[-1]] >= x[j])):
                    q.pop()
                q.append(j)
            if k - 1 - n >= 0 and x[k - 1 - n] is not None:
                cnt -= 1
            while q and q[0] < k - n:
                q.popleft()
        if k >= n and cnt >= 0.8 * n and q:
            out[k] = x[q[0]]
    return out


S = {}
for name in sorted(os.listdir(os.path.join(data, "d1"))):
    code = name[:-4]
    if code == "BTCUSDT":
        continue
    rows = read(os.path.join(data, "d1", name))
    st = {f: [None] * N for f in "ohlcvq"}
    st["first"] = pos[int(rows[0][0])]
    for r in rows:
        k = pos[int(r[0])]
        st["o"][k], st["h"][k], st["l"][k], st["c"][k], st["v"][k], st["q"][k] = (
            float(r[1]), float(r[2]), float(r[3]), float(r[4]), float(r[5]), float(r[7]))
    S[code] = st

for code, st in S.items():
    c, h, l, q = st["c"], st["h"], st["l"], st["q"]
    acc, s = [0.0] * N, 0.0
    for k in range(N):
        s += q[k] or 0.0
        if k >= 30:
            s -= q[k - 30] or 0.0
        acc[k] = s
    st["t30"] = acc
    traded, s = [0] * N, 0
    for k in range(N):
        s += 1 if q[k] else 0
        if k >= 60:
            s -= 1 if q[k - 60] else 0
        traded[k] = s
    st["t60"] = traded
    ff, last = [None] * N, None
    for k in range(N):
        if c[k] is not None:
            last = c[k]
        ff[k] = last
    st["ff"] = ff
    for n in (20, 50, 150, 200):
        st[f"ma{n}"] = sma(ff, n)
    for n in (20, 55, 250):
        st[f"hi{n}"] = prior_extreme(h, n, True)
    st["lo250"] = prior_extreme(l, 250, False)
    st["v20"] = sma([v if v else None for v in st["v"]], 20)
    tr, atr = [None] * N, [None] * N
    for k in range(st["first"] + 1, N):
        if c[k] is None or ff[k - 1] is None:
            continue
        tr[k] = max(h[k] - l[k], abs(h[k] - ff[k - 1]), abs(l[k] - ff[k - 1]))
    run, dq = 0.0, []
    for k in range(N):
        if tr[k] is not None:
            dq.append(tr[k]); run += tr[k]
            if len(dq) > 14:
                run -= dq.pop(0)
        if len(dq) == 14:
            atr[k] = run / 14
    st["atr"] = atr
    # tightness: the last 10 sessions' range over the close, against the stock's own 120 sessions before
    rng = [None] * N
    hi10, lo10 = prior_extreme(h, 10, True), prior_extreme(l, 10, False)
    for k in range(N - 1):
        if c[k] is not None and hi10[k + 1] is not None and lo10[k + 1] is not None:
            rng[k] = (hi10[k + 1] - lo10[k + 1]) / c[k]
    st["rng"] = rng
    lo_q = [None] * N
    for k in range(st["first"] + 260, N):
        w = [x for x in rng[k - 120:k] if x is not None]
        if len(w) >= 100:
            w.sort()
            lo_q[k] = (w[len(w) // 5], w[len(w) // 10])
    st["rngq"] = lo_q
    # swing highs: a high that tops the five sessions on each side
    piv = []
    for j in range(st["first"] + 5, N - 5):
        if h[j] is None:
            continue
        side = [x for x in h[j - 5:j + 6] if x is not None]
        if len(side) >= 9 and h[j] >= max(side):
            piv.append(j)
    st["piv"] = piv

# Cash: the T-bill rate after 20% tax, monthly; 20% in 2025 and 19% after the IMF series stops.
tbill = {}
for s in json.load(open(os.path.join(root, "dbn2.json")))["series"]["docs"]:
    if s["series_code"] == "M.EG.FITB_PA":
        tbill = {p: v for p, v in zip(s["period"], s["value"]) if isinstance(v, (int, float))}
cash_day = []
for d in dates:
    key = d.strftime("%Y-%m")
    rate = tbill[key] * 0.8 / 100 if key in tbill else (0.20 if d.year == 2025 else 0.19)
    cash_day.append((1 + rate) ** (1 / YEAR) - 1)

# Cash dividends from Yahoo: (stock, ex-day) -> dividend as a share of the last close before it.
DIV = {}
RAW = os.path.join(root, "raw")
for name in os.listdir(RAW):
    code = name[:-5]
    if code not in S:
        continue
    try:
        r = json.load(open(os.path.join(RAW, name)))["chart"]["result"][0]
    except Exception:
        continue
    off = r["meta"].get("gmtoffset", 7200)
    qq = r["indicators"]["quote"][0]
    rawc = sorted(((t + off) // 86400 * DAY, qq["close"][n]) for n, t in enumerate(r.get("timestamp") or []) if qq["close"][n] and qq["volume"][n])
    for d in ((r.get("events") or {}).get("dividends") or {}).values():
        day = (d["date"] + off) // 86400 * DAY
        before = [c for t, c in rawc if t < day][-1:]
        if day in pos and before and d.get("amount") and 0.002 <= d["amount"] / before[0] <= 0.30:
            DIV[(code, pos[day])] = d["amount"] / before[0]

IDX200 = sma(idx, 200)


def bull(k):
    return IDX200[k] is not None and idx[k] > IDX200[k]


_rank = {}


def rank(k):
    """Stock -> place among those listed 180 days, by the last 30 sessions' traded value."""
    if k not in _rank:
        ok = sorted(((st["t30"][k], c) for c, st in S.items() if st["c"][k] is not None and cal[k] - cal[st["first"]] >= 180 * DAY and st["t30"][k] > 0), reverse=True)
        _rank[k] = {c: i for i, (_, c) in enumerate(ok)}
    return _rank[k]


def universe(k, n=80):
    return [c for c, i in sorted(rank(k).items(), key=lambda x: x[1]) if i < n]


def cost(code, k):
    return FACTOR * (0.005 if rank(k).get(code, 999) < 30 else 0.008)


def can_fill(st, k, buy):
    if st["c"][k] is None or not st["q"][k]:
        return False
    if st["h"][k] == st["l"][k] and st["c"][k - 1]:
        move = st["c"][k] / st["c"][k - 1] - 1
        if (buy and move >= 0.045) or (not buy and move <= -0.045):
            return False
    return True


# ---------------------------------------------------------------- levels

def levels(code, k, floor_level=None, scheme="plan"):
    """The card's levels in pounds at close k: (entry low, entry high, stop, target 1, target 2)."""
    st = S[code]
    c, a = st["c"][k], st["atr"][k]
    lo = c - 0.5 * a if floor_level is None else max(floor_level, c - 0.5 * a)
    lo = min(lo, c)
    if scheme == "atr":
        return lo, c, c - 4 * a, c + 5 * a, c + 10 * a
    swing = min(x for x in st["l"][k - 9:k + 1] if x is not None)
    stop = min(swing - 0.1 * a, c - 1.5 * a)
    stop = max(stop, c - 4 * a)
    risk = c - stop
    m1, m2 = (3, 6) if scheme == "r3" else (2, 4)
    piv = st["piv"]
    i0, i1 = bisect.bisect_left(piv, k - 250), bisect.bisect_right(piv, k - 5)
    res = sorted({st["h"][j] for j in piv[i0:i1] if st["h"][j] > c})
    t1 = next((x for x in res if x >= c + m1 * risk), c + m1 * risk)
    t2 = next((x for x in res if x >= t1 + risk), c + m2 * risk if c + m2 * risk > t1 + risk * 0.999 else t1 + risk)
    return lo, c, stop, t1, t2


# ---------------------------------------------------------------- the setups

def base_ok(st, k):
    return (k - st["first"] >= 260 and st["c"][k] is not None and st["c"][k - 1] is not None and st["t60"][k] >= 57
            and st["atr"][k] and st["ma200"][k] is not None)


def up(st, k):
    return st["ma50"][k] is not None and st["c"][k] > st["ma50"][k] and st["c"][k] > st["ma200"][k]


def template(st, k):
    m50, m150, m200 = st["ma50"][k], st["ma150"][k], st["ma200"][k]
    if None in (m50, m150, m200, st["ma200"][k - 20], st["hi250"][k], st["lo250"][k]):
        return False
    c = st["c"][k]
    return c > m50 > m150 > m200 and m200 > st["ma200"][k - 20] and c >= 1.3 * st["lo250"][k] and c >= 0.75 * st["hi250"][k]


def jumped(st, k):
    return st["c"][k] / st["c"][k - 1] - 1 >= 0.08


def short_term(enter=20, vol=1.5):
    """A close above the highest high of the last `enter` sessions, on `vol` times the 20-session volume, in an uptrend,
    never the session after a jump of 8%. Best first: nearest the year's high, then the heaviest volume."""
    def find(k):
        out = []
        for code in universe(k):
            st = S[code]
            if not base_ok(st, k) or not up(st, k) or jumped(st, k):
                continue
            hi = st[f"hi{enter}"][k]
            if hi is None or st["c"][k] <= hi:
                continue
            ratio = st["v"][k] / st["v20"][k] if st["v20"][k] and st["v"][k] else 0.0
            if ratio < vol:
                continue
            near = st["c"][k] / st["hi250"][k] if st["hi250"][k] else 0.0
            out.append((-near, -ratio, code, hi))
        out.sort()
        return [(code, hi) for _, _, code, hi in out]
    return find


def mid_term(strict=True, tight=0):
    """A tight 10-session range (the stock's own lowest fifth, or tenth with tight=1, of the last 120 sessions) inside an
    uptrend (the full trend template when strict). Best first: the tightest, then nearest the year's high."""
    def find(k):
        out = []
        for code in universe(k):
            st = S[code]
            if not base_ok(st, k) or not (template(st, k) if strict else up(st, k)):
                continue
            r, qv = st["rng"][k], st["rngq"][k]
            if r is None or qv is None or r > qv[tight]:
                continue
            near = st["c"][k] / st["hi250"][k] if st["hi250"][k] else 0.0
            out.append((r, -near, code))
        out.sort()
        return [(code, None) for _, _, code in out]
    return find


def eligible(k):
    return [c for c in universe(k) if base_ok(S[c], k)]


# ---------------------------------------------------------------- the account

OUT = []   # trade log of the last run: (exit reason, net return)


def run(find, slots=8, maxnew=3, limit=10, rule_ma=None, scheme="plan", use_range=True, use_stop=True, use_targets=True,
        gate=False, twin=None, start=None):
    """One paper account. find(k) -> ranked [(code, breakout level)]. twin=seed swaps each day's picks for as many
    random eligible stocks. Returns [(equity, share held)] from `start`."""
    rng = random.Random(twin) if twin is not None else None
    cash, held, equity = 1.0, {}, 1.0
    sells, orders, out, last = {}, [], [], {}
    OUT.clear()

    def close(code, px, k, why):
        nonlocal cash
        p = held.pop(code)
        cash += p["sh"] * px * (1 - cost(code, k) / 2)
        p["got"] += p["sh"] * px * (1 - cost(code, k) / 2)
        OUT.append((p.get("best", why), p["got"] / p["paid"] - 1, k - p["k"]))

    for k in range(start, N):
        for code in list(sells):
            st = S[code]
            if code not in held:
                del sells[code]
            elif can_fill(st, k, False):
                close(code, st["o"][k], k, sells.pop(code))
            elif st["c"][k] is None:
                close(code, last[code], k, sells.pop(code))
        keep = []
        for o in orders:
            code, lv = o["code"], o["lv"]
            st = S[code]
            if code in held:
                continue
            if len(held) >= slots or not can_fill(st, k, True):
                if k < o["until"]:
                    keep.append(o)
                continue
            px = st["o"][k]
            if use_range and px > lv[1]:
                if st["l"][k] <= lv[1]:
                    px = lv[1]
                else:
                    if k < o["until"]:
                        keep.append(o)
                    continue
            amount = min(equity / slots, cash)
            if amount <= 0:
                keep.append(o)
                continue
            cash -= amount
            held[code] = {"sh": amount * (1 - cost(code, k) / 2) / px, "k": k, "paid": amount, "got": 0.0, "lv": lv, "half": False}
        orders = keep
        cash *= 1 + cash_day[k]
        for code in list(held):
            p, st = held[code], S[code]
            y = DIV.get((code, k))
            if y and p["k"] < k and st["c"][k - 1]:
                cash += p["sh"] * st["c"][k - 1] * y * 0.95
                p["got"] += p["sh"] * st["c"][k - 1] * y * 0.95
            if st["c"][k] is None or not st["q"][k] or p["k"] == k or code in sells:
                continue
            _, _, stop, t1, t2 = p["lv"]
            if use_targets and not p["half"] and st["h"][k] >= t1:
                part = p["sh"] / 2
                cash += part * t1 * (1 - cost(code, k) / 2)
                p["got"] += part * t1 * (1 - cost(code, k) / 2)
                p["sh"] -= part
                p["half"] = True
                p["best"] = "target 1, then out"
            if use_targets and st["h"][k] >= t2:
                p["best"] = "target 2"
                close(code, t2, k, "target 2")
                continue
            if use_stop and st["c"][k] < stop:
                sells[code] = "stop"
            elif limit and k - p["k"] >= limit:
                sells[code] = "time"
            elif rule_ma and st[f"ma{rule_ma}"][k] is not None and st["c"][k] < st[f"ma{rule_ma}"][k]:
                sells[code] = "rule"
        worth = 0.0
        for code, p in held.items():
            c = S[code]["c"][k]
            if c is None:
                c = last.get(code, p["paid"] / p["sh"])
                sells.setdefault(code, "gone")
            last[code] = c
            worth += p["sh"] * c
        equity = cash + worth
        out.append((equity, worth / equity if equity else 0.0))
        if k + 1 >= N or (gate and not bull(k)):
            continue
        picks = find(k)
        if rng is not None:
            n = min(len(picks), maxnew)
            pool = [c for c in eligible(k) if c not in held]
            picks = [(c, None) for c in rng.sample(pool, min(n, len(pool)))]
        pending = {o["code"] for o in orders}
        new = 0
        for code, level in picks:
            if new >= maxnew:
                break
            if code in held or code in pending:
                continue
            orders.append({"code": code, "lv": levels(code, k, level, scheme), "until": k + 3})
            new += 1
    return out


START = next(k for k, d in enumerate(dates) if d.year >= 2010)
YEARS = list(range(2010, 2027))


def mult(eq, a, b):
    ks = [i for i in range(len(eq)) if a <= dates[START + i].year <= b]
    return eq[ks[-1]] / (eq[ks[0] - 1] if ks[0] else 1.0), ks


def yearly(curve):
    eq = [e for e, _ in curve]
    return [mult(eq, y, y)[0] for y in YEARS]


def prod(v):
    out = 1.0
    for x in v:
        out *= x
    return out


def span(ys, a, b):
    return prod(ys[YEARS.index(a):YEARS.index(b) + 1])


def worst(curve, a, b):
    eq = [e for e, _ in curve]
    _, ks = mult(eq, a, b)
    peak, w = 0.0, 0.0
    for i in ks:
        peak = max(peak, eq[i]); w = min(w, eq[i] / peak - 1)
    return w


def trades_since(year):
    return [t for t in OUT]


def stats(log, months):
    if not log:
        return "no trades"
    n = len(log)
    hit1 = sum(1 for w, _, _ in log if w in ("target 1, then out", "target 2")) / n
    hit2 = sum(1 for w, _, _ in log if w == "target 2") / n
    stop = sum(1 for w, _, _ in log if w == "stop") / n
    avg = statistics.mean(r for _, r, _ in log)
    win = sum(1 for _, r, _ in log if r > 0) / n
    days = statistics.median(d for _, _, d in log)
    return f"{n / months:4.1f}/mo  win {100 * win:3.0f}%  T1 {100 * hit1:3.0f}%  T2 {100 * hit2:3.0f}%  stop {100 * stop:3.0f}%  avg {100 * avg:+5.1f}%  {days:3.0f}d"


MONTHS = (N - START) / 20.4

cash_curve, e = [], 1.0
for k in range(START, N):
    e *= 1 + cash_day[k]
    cash_curve.append((e, 0.0))
cash_y = yearly(cash_curve)
index_y = yearly([(idx[k] / idx[START - 1], 1.0) for k in range(START, N)])

print(f"{len(S)} stocks, {dates[START]} to {dates[-1]}; costs x{FACTOR:g}; what 1.00 became")
print(f"{'':52} 2015-22  2023-26  fall 23-26   each year 2015 to 2026")


def line(label, ys, curve=None, extra=""):
    w = f"{100 * worst(curve, 2023, 2026):5.0f}%" if curve else "      "
    print(f"{label:52} {span(ys, 2015, 2022):6.2f}x {span(ys, 2023, 2026):6.2f}x  {w}    "
          + " ".join(f"{100 * (v - 1):+4.0f}" for v in ys[5:]) + extra)


line("Cash (T-bills after tax)", cash_y)
line("Stand-in index (30 busiest, equal weight)", index_y)

FAMILIES = {
    "SHORT TERM (breakout, 8 slots, up to 3 new a day)": {
        f"{e}d high, vol {v:g}x, {lim} sessions": dict(find=short_term(e, v), limit=lim, maxnew=3)
        for e in (20, 55) for v in (1.0, 1.5) for lim in (10, 20)
    },
    "MID TERM (tight range, 8 slots, up to 2 new a day)": {
        f"{'template' if s else 'uptrend'}, {'tenth' if t else 'fifth'}, {lim} sessions, "
        f"{'out under ' + str(ma) + 'd avg' if ma else 'no rule exit'}": dict(find=mid_term(s, t), limit=lim, maxnew=2, rule_ma=ma)
        for s in (True, False) for t in (0, 1) for lim in (60, 120) for ma in (50, 150, None)
    },
}
DRAWS = 10
chosen = {}
for fam, versions in FAMILIES.items():
    print(f"\n{fam}")
    table, logs, curves = {}, {}, {}
    for label, kw in versions.items():
        curves[label] = run(start=START, **kw)
        logs[label] = list(OUT)
        table[label] = yearly(curves[label])
        line("  " + label, table[label], curves[label])
        print(f"  {'':50} {stats(logs[label], MONTHS)}")
    # walk-forward: each year uses the version that did best over every earlier year since 2010
    ys, picks_ = list(cash_y[:5]), []
    for i, y in enumerate(YEARS):
        if y < 2015:
            continue
        best = max(table, key=lambda key: prod(table[key][:i]))
        ys.append(table[best][i] * (0.995 if picks_ and picks_[-1] != best else 1.0))
        picks_.append(best)
    line("  => chosen from the past only", ys)
    print("     picked: " + ", ".join(f"{y}: {p}" for y, p in zip(range(2015, 2027), picks_) if y in (2015, 2019, 2023, 2026)))
    reg = max(table, key=lambda key: prod(table[key][:-1]))   # what 2026 would use: best through 2025
    chosen[fam] = (reg, versions[reg])
    twins = [yearly(run(start=START, twin=d, **versions[reg])) for d in range(DRAWS)]
    for a, b in ((2015, 2022), (2023, 2026)):
        v = sorted(span(t, a, b) for t in twins)
        print(f"     random stocks, same days and levels ({DRAWS} draws), {a}-{b}: low {v[0]:.2f}x  middle {statistics.median(v):.2f}x  high {v[-1]:.2f}x")

print("\nLEVELS AND FILTERS on the version each list would register (best through 2025)")
VARIANTS = (
    ("by the rule alone (time limit and rule exit)", dict(use_range=False, use_stop=False, use_targets=False)),
    ("entry range only", dict(use_stop=False, use_targets=False)),
    ("range and swing stop, no targets", dict(use_targets=False)),
    ("PLAN: range, swing stop, chart targets (2R min)", dict()),
    ("chart targets at 3R min", dict(scheme="r3")),
    ("the app's current ATR levels (4 / 5 / 10)", dict(scheme="atr")),
    ("PLAN + no new picks while the index is under its 200-day average", dict(gate=True)),
)
for fam, (reg, kw) in chosen.items():
    print(f"{fam.split(' (')[0]}: {reg}")
    for label, v in VARIANTS:
        c = run(start=START, **{**kw, **v})
        ys = yearly(c)
        line("  " + label, ys, c)
        print(f"  {'':50} {stats(list(OUT), MONTHS)}")
