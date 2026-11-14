"""Copies of the candles whose `open` column is the price a hand-copier would really get, for the days hourly bars exist:
   real_open  - the first trade of the next session        late  - the next session's last hour (its close)
usage: python -I fills.py <data dir> <hourly dir>"""
import json, os, sys, collections, datetime, statistics
data, src = sys.argv[1], sys.argv[2]
DAY = 86_400_000
gaps = []
for mode in ("real_open", "late"):
    os.makedirs(os.path.join("fill_" + mode, "d1"), exist_ok=True)
    open(os.path.join("fill_" + mode, "jumps.txt"), "w").write(open(os.path.join(data, "jumps.txt")).read())
for fn in os.listdir(os.path.join(data, "d1")):
    rows = [l.strip().split(",") for l in open(os.path.join(data, "d1", fn)) if l.strip()]
    hourly = {}
    p = os.path.join(src, fn[:-4] + ".json")
    if os.path.exists(p):
        try:
            r = json.load(open(p))["chart"]["result"][0]; q = r["indicators"]["quote"][0]; d = collections.defaultdict(list)
            for i, t in enumerate(r.get("timestamp") or []):
                if q["close"][i] and q["open"][i] and q["volume"][i]:
                    d[(t + 7200) // 86400 * DAY].append((q["open"][i], q["close"][i]))
            hourly = {day: (bars[0][0], bars[-1][1]) for day, bars in d.items() if len(bars) >= 2}
        except Exception:
            pass
    for mode in ("real_open", "late"):
        with open(os.path.join("fill_" + mode, "d1", fn), "w") as f:
            for r in rows:
                t, o, h, l, c = int(r[0]), float(r[1]), float(r[2]), float(r[3]), float(r[4])
                if t in hourly and float(r[7]) > 0:
                    factor = c / hourly[t][1]                      # hourly bars are raw; the daily close is adjusted
                    if 0.2 < factor < 5 and abs(hourly[t][1] * factor / c - 1) < 0.02:
                        real = min(max(hourly[t][0] * factor, l), h)
                        if mode == "real_open":
                            gaps.append(real / o - 1)
                        o = real if mode == "real_open" else c
                f.write(f"{r[0]},{o:.6f},{r[2]},{r[3]},{r[4]},{r[5]},{r[6]},{r[7]}\n")
print(f"{len(gaps)} stock-days with a real open; it sat {100 * statistics.mean(gaps):+.2f}% from the day's typical price on average (middle {100 * statistics.median(gaps):+.2f}%)")
