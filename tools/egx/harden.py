"""EGX adaptations of Signal Lab's rules, with idle money earning Egypt's T-bill rate (net of 20% tax) and EGX costs.

usage: python -I adapt.py <data dir from prepare.py (typical)> <dbn2.json with IMF series>

Orders decided at a close fill on the next market day at that day's typical price (the `open` column of the data), if the
stock traded and was not locked at its price limit. Dividends are not counted for anyone.
"""
import datetime, json, math, os, sys

data, dbn = sys.argv[1], sys.argv[2]
DAY = 86_400_000
YEAR = 245.0


def date(t):
    return datetime.datetime.fromtimestamp(t / 1000, datetime.UTC).date()


def read(path):
    return [line.split(",") for line in open(path) if line.strip()]


idx_rows = read(os.path.join(data, "d1", "BTCUSDT.csv"))
cal = [int(r[0]) for r in idx_rows]
N = len(cal)
pos = {t: k for k, t in enumerate(cal)}
idx = json.load(open("proxy_equal6.json"))["c"]
dates = [date(t) for t in cal]

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
    acc, s, q = [0.0] * N, 0.0, st["q"]
    for k in range(N):
        s += q[k] or 0.0
        if k >= 30:
            s -= q[k - 30] or 0.0
        acc[k] = s
    st["t30"] = acc
    tr, atr, c, h, l = [None] * N, [None] * N, st["c"], st["h"], st["l"]
    for k in range(st["first"] + 1, N):
        if c[k] is None:
            break
        tr[k] = max(h[k] - l[k], abs(h[k] - c[k - 1]), abs(l[k] - c[k - 1]))
        if k - st["first"] >= 14:
            atr[k] = sum(tr[k - 13:k + 1]) / 14
    st["atr"] = atr
    S[code] = st

# Cash: the T-bill rate after 20% tax, monthly; my own figures after the IMF series stops in May 2025.
tbill = {}
for s in json.load(open(dbn))["series"]["docs"]:
    if s["series_code"] == "M.EG.FITB_PA":
        tbill = {p: v for p, v in zip(s["period"], s["value"]) if isinstance(v, (int, float))}
cash_day = []
for d in dates:
    key = d.strftime("%Y-%m")
    rate = tbill[key] * 0.8 / 100 if key in tbill else (0.20 if d.year == 2025 else 0.19)
    cash_day.append((1 + rate) ** (1 / YEAR) - 1)

_uni = {}
ALLOW = None


def universe(k, mode="top40"):
    """Stocks listed 180 days, most traded first: the 40 or 80 busiest, or all that trade EGP 10m a day."""
    if k not in _uni:
        ok = [(st["t30"][k], code) for code, st in S.items() if st["c"][k] is not None and cal[k] - cal[st["first"]] >= 180 * DAY and st["t30"][k] > 0]
        _uni[k] = sorted(ok, reverse=True)
    ranked = _uni[k] if ALLOW is None else [(t, c) for t, c in _uni[k] if c in ALLOW]
    if mode == "floor":
        return [code for t, code in ranked if t / 30 >= 10e6]
    return [code for _, code in ranked[:80 if mode == "top80" else 40]]


def sma(x, n):
    out, s = [None] * len(x), 0.0
    for k in range(len(x)):
        s += x[k]
        if k >= n:
            s -= x[k - n]
        if k >= n - 1:
            out[k] = s / n
    return out


IDX200 = sma(idx, 200)


def bull(k):
    return IDX200[k] is not None and idx[k] > IDX200[k]


def can_fill(st, k, buy):
    if st["c"][k] is None or not st["q"][k]:
        return False
    if st["h"][k] == st["l"][k] and st["c"][k - 1]:
        move = st["c"][k] / st["c"][k - 1] - 1
        if (buy and move >= 0.045) or (not buy and move <= -0.045):
            return False  # one price all day at the limit: nobody on the other side
    return True


def run_slots(decide, start, cost, slots=8):
    """decide(k, held, state) -> (codes to sell, codes to buy in order of preference). Returns daily equity from `start`."""
    cash, held, equity = 1.0, {}, 1.0
    sells, buys, out, trades = [], [], [], []
    for k in range(start, N):
        for code in list(sells):
            st = S[code]
            if code in held and can_fill(st, k, False):
                cash += held.pop(code)["shares"] * st["o"][k] * (1 - cost / 2)
                sells.remove(code)
                trades.append(k)
            elif code in held and st["c"][k] is None:  # delisted from the data: paid out at the last close
                cash += held.pop(code)["shares"] * held_last[code] * (1 - cost / 2)
                sells.remove(code)
        for code in buys:
            st = S[code]
            if code in held or len(held) >= slots or not can_fill(st, k, True):
                continue
            amount = min(equity / slots, cash)
            if amount <= 0:
                break
            cash -= amount
            held[code] = {"shares": amount * (1 - cost / 2) / st["o"][k], "k": k, "price": st["o"][k], "atr": st["atr"][k - 1]}
        buys = []
        cash *= 1 + cash_day[k]
        for code, p in held.items():
            y = DIV.get((code, k))
            if y and p["k"] < k and S[code]["c"][k - 1]:
                cash += p["shares"] * S[code]["c"][k - 1] * y * 0.95  # the dividend after 5% tax
        worth = 0.0
        for code, p in held.items():
            c = S[code]["c"][k]
            if c is None:
                c = held_last.get(code, p["price"])
                if code not in sells:
                    sells.append(code)
            held_last[code] = c
            worth += p["shares"] * c
        equity = cash + worth
        out.append((equity, worth / equity))
        s, b = decide(k, held)
        for code in s:
            if code not in sells:
                sells.append(code)
        buys = [c for c in b if c not in held]
    return out, trades


held_last = {}


def breakout(gate, enter=50, leave=20, max_days=60, uni="top40", chase=True):
    def decide(k, held):
        s = []
        for code, p in held.items():
            st = S[code]
            c = st["c"][k]
            if c is None:
                continue
            lows = [x for x in st["l"][k - leave:k] if x is not None]
            stop = p["price"] - 3 * p["atr"] if p["atr"] else 0
            if (lows and c < min(lows)) or (max_days and k - p["k"] >= max_days) or c < stop:
                s.append(code)
        b = []
        if not gate or bull(k):
            for code in universe(k, uni):
                st = S[code]
                if code in held or k - st["first"] < enter:
                    continue
                highs = st["h"][k - enter:k]
                if None not in highs and st["c"][k] > max(highs):
                    if not chase and st["c"][k - 1] and st["c"][k] / st["c"][k - 1] - 1 >= 0.08:
                        continue  # do not chase a jump: wait for the next new high
                    b.append(code)
        return s, b
    return decide


def trend_timer(days=50):
    avg = {}

    def decide(k, held):
        s, b = [], []
        top = universe(k)[:10]
        for code in set(held) | set(top):
            st = S[code]
            if st["c"][k] is None or st["c"][k - 1] is None:
                continue
            if code not in avg:
                f = st["first"]
                series = sma([x for x in st["c"][f:] if x is not None], days)
                avg[code] = [None] * f + series + [None] * (N - f - len(series))
            a, a0 = avg[code][k], avg[code][k - 1]
            if a is None or a0 is None:
                continue
            if code in held and st["c"][k] < a:
                s.append(code)
            elif code in top and code not in held and st["c"][k] > a and st["c"][k - 1] <= a0:
                b.append(code)
        return s, [c for c in top if c in b]
    return decide


def momentum(gate, form=126, skip=21, top=8, uni="top40"):
    def decide(k, held):
        if k + 1 >= N or dates[k + 1].month == dates[k].month:
            return [], []
        if gate and not bull(k):
            return list(held), []
        scores = []
        for code in universe(k, uni):
            st = S[code]
            if k - form - skip < st["first"]:
                continue
            a, b = st["c"][k - form - skip], st["c"][k - skip]
            if a and b:
                scores.append((b / a - 1, code))
        want = [code for _, code in sorted(scores, reverse=True)[:top]]
        return [c for c in held if c not in want], want
    return decide


def index_timer(days, start, cost):
    """In the index when its close is above its average, else in cash; a switch takes effect one day later."""
    avg, eq, out, inside, switches = sma(idx, days), 1.0, [], False, 0
    for k in range(start, N):
        want = avg[k - 2] is not None and idx[k - 2] > avg[k - 2]
        if want != inside:
            eq *= 1 - cost / 2
            inside = want
            switches += 1
        eq *= idx[k] / idx[k - 1] if inside else 1 + cash_day[k]
        out.append((eq, 1.0 if inside else 0.0))
    return out, switches



cashidx, _e = [], 1.0
for _k in range(N):
    _e *= 1 + cash_day[_k]
    cashidx.append(_e)

_avg = {}


def stock_avg(code, days):
    if (code, days) not in _avg:
        st = S[code]
        f = st["first"]
        series = sma([x for x in st["c"][f:] if x is not None], days)
        _avg[(code, days)] = [None] * f + series + [None] * (N - f - len(series))
    return _avg[(code, days)]


def index_vs_cash(days, start, cost):
    """In the market when it beat cash over the last `days` sessions, else in cash; a switch takes effect a day later."""
    eq, out, inside = 1.0, [], False
    for k in range(start, N):
        j = k - 2
        want = j - days >= 0 and idx[j] / idx[j - days] > cashidx[j] / cashidx[j - days]
        if want != inside:
            eq *= 1 - cost / 2
            inside = want
        eq *= idx[k] / idx[k - 1] if inside else 1 + cash_day[k]
        out.append((eq, 1.0 if inside else 0.0))
    return out


def beat_cash(form=126, skip=21, top=8, uni="top40"):
    """Monthly: the strongest stocks over six months, but only those that beat cash over the same months."""
    def decide(k, held):
        if k + 1 >= N or dates[k + 1].month == dates[k].month:
            return [], []
        scores = []
        for code in universe(k, uni):
            st = S[code]
            if k - form - skip < st["first"]:
                continue
            a, b = st["c"][k - form - skip], st["c"][k - skip]
            if a and b and b / a > cashidx[k - skip] / cashidx[k - form - skip]:
                scores.append((b / a - 1, code))
        want = [code for _, code in sorted(scores, reverse=True)[:top]]
        return [c for c in held if c not in want], want
    return decide


def dip(drop, hold=10, days=200, uni="top40"):
    """A stock above its 200-day average that fell `drop` in five sessions; sold after `hold` sessions or below the average."""
    def decide(k, held):
        s = []
        for code, p in held.items():
            st, a = S[code], stock_avg(code, days)[k]
            if st["c"][k] is None:
                continue
            if k - p["k"] >= hold or (a is not None and st["c"][k] < a):
                s.append(code)
        b = []
        for code in universe(k, uni):
            st, a = S[code], stock_avg(code, days)[k]
            if code in held or a is None or k - 5 < st["first"] or not st["c"][k - 5]:
                continue
            if st["c"][k] > a and st["c"][k] / st["c"][k - 5] - 1 <= drop:
                b.append(code)
        return s, b
    return decide



# Cash dividends from Yahoo: (stock, ex-day) -> dividend as a share of the last close before it.
DIV, DIVDAYS = {}, {}
RAW = sys.argv[5]
for name in os.listdir(RAW):
    code = name[:-5]
    if code not in S:
        continue
    try:
        r = json.load(open(os.path.join(RAW, name)))["chart"]["result"][0]
    except Exception:
        continue
    off = r["meta"].get("gmtoffset", 7200)
    q = r["indicators"]["quote"][0]
    rawc = sorted(((t + off) // 86400 * DAY, q["close"][n]) for n, t in enumerate(r.get("timestamp") or []) if q["close"][n] and q["volume"][n])
    for d in ((r.get("events") or {}).get("dividends") or {}).values():
        day = (d["date"] + off) // 86400 * DAY
        before = [c for t, c in rawc if t < day][-1:]
        if day in pos and before and d.get("amount") and 0.002 <= d["amount"] / before[0] <= 0.30:
            DIV[(code, pos[day])] = d["amount"] / before[0]
            DIVDAYS.setdefault(code, []).append(pos[day])


def month_end(k):
    return k + 1 < N and dates[k + 1].month != dates[k].month


def hold30():
    """The 30 busiest stocks, re-picked at the start of March and September, never timed."""
    def decide(k, held):
        if held and not (month_end(k) and dates[k + 1].month in (3, 9)):
            return [], []
        want = universe(k)[:30]
        return [c for c in held if c not in want], want
    return decide


def low_risk(top=8, uni="top80", uptrend=True):
    """Monthly: the calmest stocks (60-day swings), if `uptrend` only those above their 200-day average."""
    def decide(k, held):
        if not month_end(k):
            return [], []
        scores = []
        for code in universe(k, uni):
            st = S[code]
            if k - 61 < st["first"]:
                continue
            r = [st["c"][j] / st["c"][j - 1] - 1 for j in range(k - 59, k + 1)]
            m = sum(r) / len(r)
            a = stock_avg(code, 200)[k]
            if uptrend and (a is None or st["c"][k] < a):
                continue
            scores.append((sum((x - m) ** 2 for x in r), code))
        want = [code for _, code in sorted(scores)[:top]]
        return [c for c in held if c not in want], want
    return decide


def high_yield(top=8, uni="top80", uptrend=False):
    """Monthly: the highest dividend payers over the last 250 sessions."""
    def decide(k, held):
        if not month_end(k):
            return [], []
        scores = []
        for code in universe(k, uni):
            y = sum(DIV[(code, j)] for j in DIVDAYS.get(code, ()) if k - 250 < j <= k)
            a = stock_avg(code, 200)[k]
            if y > 0 and not (uptrend and (a is None or S[code]["c"][k] < a)):
                scores.append((y, code))
        want = [code for _, code in sorted(scores, reverse=True)[:top]]
        return [c for c in held if c not in want], want
    return decide


def dividend_season(lead=30, min_yield=0.03, uni="top80"):
    """Buy a stock `lead` sessions before the anniversary of last year's dividend; sell the day before this year's."""
    def decide(k, held):
        s = [c for c, p in held.items() if (c, k + 2) in DIV or (c, k + 1) in DIV or k - p["k"] >= 2 * lead]
        b = []
        for code in universe(k, uni):
            for j in DIVDAYS.get(code, ()):
                if DIV[(code, j)] >= min_yield and 190 <= k - j <= 260 and (k - j) == 245 - lead:
                    b.append((DIV[(code, j)], code))
        return s, [c for _, c in sorted(b, reverse=True)]
    return decide



def mult(eq, start, a, b):
    ks = [i for i in range(len(eq)) if a <= dates[start + i].year <= b]
    return eq[ks[-1]] / (eq[ks[0] - 1] if ks[0] else 1.0), ks


def report(name, curve, start, sells=None):
    eq = [e for e, _ in curve]
    years = " ".join(f"{100 * (mult(eq, start, y, y)[0] - 1):+5.0f}%" for y in (2023, 2024, 2025, 2026))
    recent, ks = mult(eq, start, 2023, 2026)
    peak, worst = 0.0, 0.0
    for i in ks:
        peak = max(peak, eq[i])
        worst = min(worst, eq[i] / peak - 1)
    held = sum(curve[i][1] for i in ks) / len(ks)
    old = mult(eq, start, 2010, 2022)[0]
    per_month = "" if sells is None else f"{2 * sum(1 for k in sells if dates[k].year >= 2023) / 46.4:4.0f}"
    print(f"{name:<50} {years}  {recent:5.2f}x {100 * worst:5.0f}% {100 * held:4.0f}%  {old:5.2f}x  {per_month}")


start = next(k for k, d in enumerate(dates) if d.year >= 2010)
BASE, STRESS = float(sys.argv[3]), float(sys.argv[4])
print(f"{len(S)} stocks; costs {100 * BASE:.1f}% a round trip (then {100 * STRESS:.1f}%); money in pounds")
print(f"{'account':<50}   2023   2024   2025   2026  2023-26 worst held  2010-22  orders a month")
cash, e = [], 1.0
for k in range(start, N):
    e *= 1 + cash_day[k]
    cash.append((e, 0.0))
report("Cash (T-bills after tax)", cash, start)
report("Stand-in index (30 busiest, equal weight)", [(idx[k] / idx[start - 1], 1.0) for k in range(start, N)], start)
print("real EGX30                                          +70%   +20%   +41%   +23%   3.52x")
curve, n = index_timer(200, start, BASE)
report("Market switch, 200-day average", curve, start)
report("Market vs cash over 12 months", index_vs_cash(245, start, BASE), start)
report("Market vs cash over 6 months", index_vs_cash(122, start, BASE), start)

import random, statistics


def calm(top=8, uni="top80", trend=200, window=60, min_traded=57):
    def decide(k, held):
        if not month_end(k):
            return [], []
        scores = []
        for code in universe(k, uni):
            st = S[code]
            if k - max(window, 60) - 1 < st["first"] or sum(1 for j in range(k - 59, k + 1) if st["q"][j]) < min_traded:
                continue
            if trend:
                a = stock_avg(code, trend)[k]
                if a is None or st["c"][k] < a:
                    continue
            r = [st["c"][j] / st["c"][j - 1] - 1 for j in range(k - window + 1, k + 1)]
            m = sum(r) / len(r)
            scores.append((sum((x - m) ** 2 for x in r), code))
        want = [code for _, code in sorted(scores)[:top]]
        return [c for c in held if c not in want], want
    return decide


def picks(seed, top=8, uni="top80", trend=200, min_traded=57):
    rng = random.Random(seed)
    def decide(k, held):
        if not month_end(k):
            return [], []
        ok = []
        for c in universe(k, uni):
            st = S[c]
            if k - 61 < st["first"] or sum(1 for j in range(k - 59, k + 1) if st["q"][j]) < min_traded:
                continue
            a = stock_avg(c, trend)[k] if trend else None
            if trend and (a is None or st["c"][k] < a):
                continue
            ok.append(c)
        want = ok if top is None else rng.sample(ok, min(top, len(ok)))
        return [c for c in held if c not in want], want
    return decide


def two(curve):
    eq = [e for e, _ in curve]
    return mult(eq, start, 2023, 2026)[0], mult(eq, start, 2010, 2022)[0]


def go(decide, slots=8):
    held_last.clear()
    return run_slots(decide, start, BASE, slots)[0]


def monthly(curve):
    eq = [e for e, _ in curve]
    out, last = {}, 1.0
    for i, e in enumerate(eq):
        if start + i + 1 >= N or dates[start + i + 1].month != dates[start + i].month:
            out[dates[start + i].strftime("%Y-%m")] = e / last - 1
            last = e
    return out


print("A. EVERY SETTING (96 versions): holdings x swing window x trend filter x universe")
grid = {}
for top in (5, 8, 12, 20):
    for window in (20, 60, 120, 250):
        for trend in (0, 100, 200):
            for uni in ("top40", "top80"):
                grid[(top, window, trend, uni)] = two(go(calm(top, uni, trend, window), top))
for era, i, market, cashx in (("2023-26", 0, 3.76, 1.98), ("2010-22", 1, 0.93, 3.65)):
    v = sorted(g[i] for g in grid.values())
    print(f"  {era}: lowest {v[0]:.2f}x, quarter {v[24]:.2f}x, middle {v[48]:.2f}x, three quarters {v[72]:.2f}x, highest {v[-1]:.2f}x; beat the market ({market}x) in {sum(x > market for x in v)} of 96, beat cash ({cashx}x) in {sum(x > cashx for x in v)}")
for name, pos_, vals in (("holdings", 0, (5, 8, 12, 20)), ("window", 1, (20, 60, 120, 250)), ("trend filter", 2, (0, 100, 200)), ("universe", 3, ("top40", "top80"))):
    print(f"  middle result by {name}: " + "   ".join(f"{v}: {statistics.median(g[0] for key, g in grid.items() if key[pos_] == v):.2f}x / {statistics.median(g[1] for key, g in grid.items() if key[pos_] == v):.2f}x" for v in vals))

print("B. AGAINST RANDOM PICKS (8 stocks in an uptrend, same universe, 100 draws)")
base = two(go(calm()))
draws = [two(go(picks(seed))) for seed in range(100)]
for era, i in (("2023-26", 0), ("2010-22", 1)):
    v = sorted(d[i] for d in draws)
    print(f"  {era}: calm {base[i]:.2f}x; random middle {v[50]:.2f}x, best {v[-1]:.2f}x; calm beats {sum(x < base[i] for x in v)} of 100")

print("C. AGAINST ALL UPTREND STOCKS, month by month")
cm, am = monthly(go(calm())), monthly(go(picks(0, top=None), 80))
for era, a, b in (("2010-22", "2010", "2022"), ("2023-26", "2023", "2026")):
    d = [cm[m] - am[m] for m in cm if a <= m[:4] <= b]
    mean = sum(d) / len(d); sd = statistics.stdev(d)
    print(f"  {era}: calm ahead by {100 * mean:+.2f}% a month on average, ahead in {sum(x > 0 for x in d)} of {len(d)} months, t {mean / sd * len(d) ** 0.5:+.2f}")
worst = sorted(am, key=lambda m: am[m])[:12]
print("  the 12 worst months for uptrend stocks: " + ", ".join(f"{m} {100 * am[m]:+.0f}% (calm {100 * cm[m]:+.0f}%)" for m in sorted(worst)))
best = sorted(am, key=lambda m: -am[m])[:12]
print("  the 12 best months: " + ", ".join(f"{m} {100 * am[m]:+.0f}% (calm {100 * cm[m]:+.0f}%)" for m in sorted(best)))

print("D. ONLY STOCKS WITH TRUSTED PRICES (no cleaned jump since 2009, and Yahoo records their dividends or splits)")
dirty = {l.split()[0] for l in open(os.path.join(sys.argv[1], "jumps.txt")) if l.split()[1] >= "2009"}
tracked = set(DIVDAYS)
ALLOW = {c for c in S if c not in dirty and c in tracked}
_uni.clear()
print(f"  {len(ALLOW)} of {len(S)} stocks")
base = two(go(calm()))
draws = [two(go(picks(seed))) for seed in range(100)]
allup = two(go(picks(0, top=None), 80))
h30 = two(go(hold30(), 30))
for era, i in (("2023-26", 0), ("2010-22", 1)):
    v = sorted(d[i] for d in draws)
    print(f"  {era}: calm {base[i]:.2f}x; all uptrend stocks {allup[i]:.2f}x; holding the 30 busiest {h30[i]:.2f}x; random middle {v[50]:.2f}x, best {v[-1]:.2f}x; calm beats {sum(x < base[i] for x in v)} of 100")
