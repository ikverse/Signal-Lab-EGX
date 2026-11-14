"""Turns Yahoo's EGX daily history into the candle files the whole-account test reads (research/data layout).

usage: python -I prepare.py <raw dir> <out dir> <open: prev|typical>

- One market calendar: every date on which at least 20 stocks traded. A stock that did not trade on a market day gets a
  flat candle at its last close with no volume, from its first candle to its last.
- Yahoo's "open" for EGX is the previous close, not a traded price. `prev` keeps it, pulled inside the day's range;
  `typical` replaces it with (high + low + close) / 3, a stand-in for an order filled some time during the session.
- BTCUSDT.csv is a stand-in market index: the 30 busiest stocks by 30-session turnover (at least 180 days listed),
  re-picked on the first session of each month, equal weight. Its turnover is zero, so it is never watched as a stock.
"""
import datetime, json, os, sys

raw, out, open_mode = sys.argv[1], sys.argv[2], sys.argv[3]
DAY = 86_400_000
os.makedirs(os.path.join(out, "d1"), exist_ok=True)
os.makedirs(os.path.join(out, "funding"), exist_ok=True)

stocks = {}
jumps = []
for name in sorted(os.listdir(raw)):
    code = name[:-5]
    try:
        r = json.load(open(os.path.join(raw, name)))["chart"]["result"][0]
    except Exception:
        continue
    ts = r.get("timestamp") or []
    q = r["indicators"]["quote"][0]
    off = r["meta"].get("gmtoffset", 7200)
    rows = {}
    for i, t in enumerate(ts):
        o, h, l, c, v = q["open"][i], q["high"][i], q["low"][i], q["close"][i], q["volume"][i]
        if not c or c <= 0 or not h or not l or h <= 0 or l <= 0 or not v:
            continue  # no price, or a placeholder row for a day the stock did not trade
        day = (t + off) // 86400 * DAY
        h, l = max(h, l, c), min(h, l, c)
        rows[day] = [o if o and o > 0 else c, h, l, c, float(v or 0)]
    if len(rows) < 60:
        continue
    days = sorted(rows)
    # EGX caps a day's move at 20%, so a larger close-to-close move is a bonus issue, split or bad row that Yahoo did not
    # adjust. Such a day is taken as flat: every earlier price is scaled by the jump (turnover, kept apart, is untouched).
    raw_close = [rows[d][3] for d in days]
    f = [1.0] * len(days)
    for k in range(len(days) - 1, 0, -1):
        step = raw_close[k] / raw_close[k - 1]
        jump = abs(step - 1) > 0.21
        if jump:
            jumps.append((code, datetime.date.fromtimestamp(days[k] / 1000).isoformat(), round(step - 1, 3)))
        f[k - 1] = f[k] * (step if jump else 1.0)
    for k, d in enumerate(days):
        o, h, l, c, v = rows[d]
        rows[d] = [o * f[k], h * f[k], l * f[k], c * f[k], v, c * v]
    stocks[code] = rows

# The market calendar.
count = {}
for rows in stocks.values():
    for d, row in rows.items():
        if row[4] > 0:
            count[d] = count.get(d, 0) + 1
calendar = sorted(d for d, n in count.items() if n >= 20)
index_of = {d: i for i, d in enumerate(calendar)}
print(f"{len(stocks)} stocks, {len(calendar)} market days from {datetime.date.fromtimestamp(calendar[0] / 1000)} to {datetime.date.fromtimestamp(calendar[-1] / 1000)}")
print(f"{len(jumps)} one-day moves beyond 21% taken as flat (unadjusted corporate actions, bad rows, or days without a price limit)")

# Each stock on the calendar.
panel = {}
for code, rows in stocks.items():
    days = [d for d in sorted(rows) if d in index_of]
    if len(days) < 60:
        continue
    first, last = index_of[days[0]], index_of[days[-1]]
    series, prev = [], None
    for k in range(first, last + 1):
        d = calendar[k]
        if d in rows:
            o, h, l, c, v, qv = rows[d]
            if open_mode == "typical":
                o = (h + l + c) / 3
            else:
                o = min(max(prev if prev else o, l), h)
        else:
            o = h = l = c = prev
            v = qv = 0.0
        series.append((d, o, h, l, c, v, qv))
        prev = c
    panel[code] = (first, series)

for code, (first, series) in panel.items():
    with open(os.path.join(out, "d1", code + ".csv"), "w") as f:
        for d, o, h, l, c, v, qv in series:
            f.write(f"{d},{o:.6f},{h:.6f},{l:.6f},{c:.6f},{v:.0f},{d + DAY - 1},{qv:.2f}\n")

# The stand-in index.
n = len(calendar)
close = {code: [None] * n for code in panel}
turn = {code: [0.0] * n for code in panel}
for code, (first, series) in panel.items():
    for k, row in enumerate(series):
        close[code][first + k] = row[4]
        turn[code][first + k] = row[6]
turn30 = {}
for code in panel:
    acc, s = [0.0] * n, 0.0
    for k in range(n):
        s += turn[code][k]
        if k >= 30:
            s -= turn[code][k - 30]
        acc[k] = s
    turn30[code] = acc
level, members, month = 1000.0, [], None
with open(os.path.join(out, "d1", "BTCUSDT.csv"), "w") as f:
    for k in range(n):
        d = calendar[k]
        before = level
        if members:
            rets = [close[c][k] / close[c][k - 1] - 1 for c in members if close[c][k] and close[c][k - 1]]
            if rets:
                level *= 1 + sum(rets) / len(rets)
        m = datetime.date.fromtimestamp(d / 1000).strftime("%Y-%m")
        if m != month:
            month = m
            eligible = [(turn30[c][k], c) for c in panel if close[c][k] and d - calendar[panel[c][0]] >= 180 * DAY and turn30[c][k] > 0]
            members = [c for _, c in sorted(eligible, reverse=True)[:30]]
        f.write(f"{d},{before:.6f},{max(before, level):.6f},{min(before, level):.6f},{level:.6f},0,{d + DAY - 1},0\n")
print(f"{len(panel)} stock files and the stand-in index written to {out}; index ends at {level:.0f} from 1000")
with open(os.path.join(out, "jumps.txt"), "w") as f:
    for j in jumps:
        f.write(f"{j[0]} {j[1]} {j[2]:+.3f}\n")
