"""Exports what the research scripts read besides candles into plain CSV for the engine's whole-account test (`research/`).

usage: python -I export.py <data dir from prepare.py> <dbn2.json with the IMF series> <raw dir from download.py>

Writes into the data dir:
- dividends.csv  code,ex-day (ms, the first session without the dividend),dividend as a share of the last raw close before it
- cash.csv       yyyy-mm,yearly cash rate after tax (T-bill rate less 20% tax; 20% in 2025 and 19% after, where the IMF
                 series stops), one row per month of the market calendar

Both follow `levels.py` exactly, so the engine sees the same dividends and the same cash as the research did.
"""
import datetime, json, os, sys

data, dbn, raw = sys.argv[1], sys.argv[2], sys.argv[3]
DAY = 86_400_000

cal = [int(line.split(",")[0]) for line in open(os.path.join(data, "d1", "BTCUSDT.csv")) if line.strip()]
pos = {t: k for k, t in enumerate(cal)}
codes = {name[:-4] for name in os.listdir(os.path.join(data, "d1")) if name != "BTCUSDT.csv"}

tbill = {}
for s in json.load(open(dbn))["series"]["docs"]:
    if s["series_code"] == "M.EG.FITB_PA":
        tbill = {p: v for p, v in zip(s["period"], s["value"]) if isinstance(v, (int, float))}
months = sorted({datetime.datetime.fromtimestamp(t / 1000, datetime.UTC).date().strftime("%Y-%m") for t in cal})
with open(os.path.join(data, "cash.csv"), "w") as f:
    for key in months:
        rate = tbill[key] * 0.8 / 100 if key in tbill else (0.20 if key.startswith("2025") else 0.19)
        f.write(f"{key},{rate!r}\n")

rows = {}  # keyed like levels.py's DIV, so a second dividend on the same day replaces the first
for name in sorted(os.listdir(raw)):
    code = name[:-5]
    if code not in codes:
        continue
    try:
        r = json.load(open(os.path.join(raw, name)))["chart"]["result"][0]
    except Exception:
        continue
    off = r["meta"].get("gmtoffset", 7200)
    q = r["indicators"]["quote"][0]
    rawc = sorted(((t + off) // 86400 * DAY, q["close"][n]) for n, t in enumerate(r.get("timestamp") or []) if q["close"][n] and q["volume"][n])
    for d in ((r.get("events") or {}).get("dividends") or {}).values():
        day = (d["date"] + off) // 86400 * DAY
        before = [c for t, c in rawc if t < day][-1:]
        if day in pos and before and d.get("amount") and 0.002 <= d["amount"] / before[0] <= 0.30:
            rows[(code, day)] = d["amount"] / before[0]
with open(os.path.join(data, "dividends.csv"), "w") as f:
    for (code, day), y in sorted(rows.items()):
        f.write(f"{code},{day},{y!r}\n")
print(f"{len(months)} months of cash rates and {len(rows)} dividends written to {data}")
