"""An optimistic copy of the candles: every unexplained gap-down (the day's high stayed 6% under the last close, the market did
not fall 3%, and no dividend of 4% is recorded that day) is taken as a hidden bonus issue and flattened. The truth lies
between this and the plain cleaning. usage: python -I optimistic.py <data dir> <raw dir> <out dir>"""
import json, os, sys, collections
data, raw, out = sys.argv[1], sys.argv[2], sys.argv[3]
DAY = 86_400_000
os.makedirs(os.path.join(out, "d1"), exist_ok=True)
def read(p): return [l.strip().split(",") for l in open(p) if l.strip()]
idx_rows = read(os.path.join(data, "d1", "BTCUSDT.csv"))
mkt = {}
for a, b in zip(idx_rows, idx_rows[1:]): mkt[int(b[0])] = float(b[4]) / float(a[4]) - 1
div = collections.defaultdict(dict)
for fn in os.listdir(raw):
    try: r = json.load(open(os.path.join(raw, fn)))["chart"]["result"][0]
    except Exception: continue
    off = r["meta"].get("gmtoffset", 7200); q = r["indicators"]["quote"][0]
    rc = sorted(((t + off) // 86400 * DAY, q["close"][n]) for n, t in enumerate(r.get("timestamp") or []) if q["close"][n] and q["volume"][n])
    for d in ((r.get("events") or {}).get("dividends") or {}).values():
        day = (d["date"] + off) // 86400 * DAY; b = [c for t, c in rc if t < day][-1:]
        if b: div[fn[:-5]][day] = d["amount"] / b[0]
fixed = 0
for fn in os.listdir(os.path.join(data, "d1")):
    rows = read(os.path.join(data, "d1", fn)); code = fn[:-4]
    if code != "BTCUSDT":
        f = [1.0] * len(rows)
        for k in range(len(rows) - 1, 0, -1):
            t, h, c, q = int(rows[k][0]), float(rows[k][2]), float(rows[k][4]), float(rows[k][7])
            c0 = float(rows[k - 1][4])
            hit = q > 0 and h < 0.94 * c0 and mkt.get(t, 0) > -0.03 and div[code].get(t, 0) < 0.04
            f[k - 1] = f[k] * (c / c0 if hit else 1.0)
            fixed += hit
        for k, r in enumerate(rows):
            for col in (1, 2, 3, 4): r[col] = f"{float(r[col]) * f[k]:.6f}"
    with open(os.path.join(out, "d1", fn), "w") as fh:
        for r in rows: fh.write(",".join(r) + "\n")
open(os.path.join(out, "jumps.txt"), "w").write(open(os.path.join(data, "jumps.txt")).read())
print(fixed, "gap-downs flattened")
