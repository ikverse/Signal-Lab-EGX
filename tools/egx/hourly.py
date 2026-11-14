"""Two years of hourly bars for the 100 busiest EGX stocks, from Yahoo's ISIN symbols. Public data, no key.
usage: python -I hourly.py <yahoo_symbols.json> <data dir> <out dir>"""
import json, os, sys, time, urllib.request
symbols, data, out = sys.argv[1], sys.argv[2], sys.argv[3]
os.makedirs(out, exist_ok=True)
isin = {r["egx"]: r["yahoo"] for r in json.load(open(symbols, encoding="utf-8"))}
turn = []
for name in os.listdir(os.path.join(data, "d1")):
    code = name[:-4]
    if code == "BTCUSDT": continue
    rows = open(os.path.join(data, "d1", name)).read().split()[-30:]
    turn.append((sum(float(r.split(",")[7]) for r in rows), code))
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/126 Safari/537.36"
n = 0
for _, code in sorted(turn, reverse=True)[:100]:
    path = os.path.join(out, code + ".json")
    if os.path.exists(path) or code not in isin: continue
    url = f"https://query1.finance.yahoo.com/v8/finance/chart/{isin[code]}?range=2y&interval=1h"
    for attempt in range(3):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": UA}), timeout=40) as r:
                open(path, "wb").write(r.read()); n += 1
            break
        except Exception as e:
            if getattr(e, "code", None) == 404: break
            time.sleep(3 + 4 * attempt)
    time.sleep(0.4)
print(n, "downloaded", flush=True)
