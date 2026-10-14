import urllib.request, json, time

t = time.time()
params = {
    'startDate': '2021-05-01', 'endDate': '2026-04-28', 'capital': 100000,
    'codes': [], 'excludeCodes': ['161226'], 'strategy': 'PREMIUM', 'perTradeAmount': 0,
    'premiumConfig': {'devPct': 5, 'maxSubUnit': 0, 'turnoverWan': 500,
                      'excludeQDII': True, 'requireSubscribe': True, 'slippagePct': 0.3},
    'feeConfig': {'subscribeRate': 0.0012, 'redeemRate7d': 0.005, 'redeemRateShort': 0.015, 'commission': 0.00025}
}
req = urllib.request.Request('http://localhost:8080/api/backtest/run',
    data=json.dumps(params).encode(), headers={'Content-Type': 'application/json'})
d = json.loads(urllib.request.urlopen(req, timeout=300).read())
elapsed = time.time() - t
print(f"Time: {elapsed:.1f}s")
print(f"Trades: {d['summary']['totalTrades']}")
print(f"Return: {d['summary']['totalReturnPct']:.2f}%")
print(f"Keys: {list(d['summary'].keys())}")
