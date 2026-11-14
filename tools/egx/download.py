"""Daily history of every EGX stock Yahoo carries under its legacy TICKER.CA symbol. Public data, no key.
usage: python -I download.py <yahoo_symbols.json> <out dir>
"""
import json, os, sys, time, urllib.request

symbols_file, out = sys.argv[1], sys.argv[2]
os.makedirs(out, exist_ok=True)
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/126 Safari/537.36"
codes = sorted({row["egx"] for row in json.load(open(symbols_file, encoding="utf-8"))})
ok = missing = 0
for code in codes:
    path = os.path.join(out, code + ".json")
    if os.path.exists(path):
        ok += 1
        continue
    url = f"https://query1.finance.yahoo.com/v8/finance/chart/{code}.CA?period1=0&period2=9999999999&interval=1d&events=div%2Csplits"
    body = None
    for attempt in range(4):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": UA}), timeout=40) as r:
                body = r.read()
            break
        except Exception as e:  # noqa
            if getattr(e, "code", None) == 404:
                break
            time.sleep(3 + 5 * attempt)
    if body is None:
        missing += 1
        print("no legacy feed:", code, flush=True)
        continue
    with open(path, "wb") as f:
        f.write(body)
    ok += 1
    time.sleep(0.4)
print(f"{ok} downloaded, {missing} missing of {len(codes)}", flush=True)
