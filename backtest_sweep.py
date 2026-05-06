import urllib.request, json, sys

BASE = 'http://localhost:8080/api/backtest/run'

def run_bt(params):
    req = urllib.request.Request(BASE,
        data=json.dumps(params).encode(),
        headers={'Content-Type': 'application/json'})
    return json.loads(urllib.request.urlopen(req).read())

def fmt(s):
    return (f"trades={s['totalTrades']:3d}  win={s['winRate']:5.1f}%  "
            f"return={s['totalReturnPct']:7.1f}%  maxDD={s['maxDrawdownPct']:5.1f}%  "
            f"avgHold={s['avgHoldDays']:4.1f}d  fees={s['totalFees']:7.0f}  "
            f"maxProfit={s['maxSingleProfit']:8.0f}  maxLoss={s['maxSingleLoss']:8.0f}  "
            f"util={s['capitalUtilization']:4.1f}%")

common = {
    'startDate': '2021-05-01',
    'endDate': '2026-04-28',
    'capital': 100000,
    'codes': ['161226'],
    'strategy': 'PREMIUM',
    'perTradeAmount': 0,
    'feeConfig': {
        'subscribeRate': 0.0012,
        'redeemRate7d': 0.005,
        'redeemRateShort': 0.015,
        'commission': 0.00025
    }
}

# === Test 1: Vary premium threshold (slippage=0.3%) ===
print("=" * 100)
print("TEST 1: Premium threshold sweep (slippage=0.3%, requireSubscribe=True, perTrade=auto20%)")
print("=" * 100)
for pct in [2, 3, 5, 8, 10, 15, 20, 25, 30]:
    params = {**common, 'premiumConfig': {
        'devPct': pct, 'maxSubUnit': 0, 'turnoverWan': 0,
        'excludeQDII': False, 'requireSubscribe': True, 'slippagePct': 0.3
    }}
    r = run_bt(params)
    print(f"  threshold={pct:2d}%  {fmt(r['summary'])}")

# === Test 2: Vary slippage (threshold=10%) ===
print("\n" + "=" * 100)
print("TEST 2: Slippage sweep (threshold=10%, requireSubscribe=True)")
print("=" * 100)
for slip in [0, 0.1, 0.3, 0.5, 1.0, 2.0]:
    params = {**common, 'premiumConfig': {
        'devPct': 10, 'maxSubUnit': 0, 'turnoverWan': 0,
        'excludeQDII': False, 'requireSubscribe': True, 'slippagePct': slip
    }}
    r = run_bt(params)
    print(f"  slippage={slip:.1f}%  {fmt(r['summary'])}")

# === Test 3: Fixed per-trade amount (threshold=10%, slippage=0.3%) ===
print("\n" + "=" * 100)
print("TEST 3: Per-trade amount sweep (threshold=10%, slippage=0.3%)")
print("=" * 100)
for amt in [0, 1000, 5000, 10000, 20000]:
    params = {**common, 'perTradeAmount': amt, 'premiumConfig': {
        'devPct': 10, 'maxSubUnit': 0, 'turnoverWan': 0,
        'excludeQDII': False, 'requireSubscribe': True, 'slippagePct': 0.3
    }}
    r = run_bt(params)
    label = f"{amt:6d}" if amt > 0 else " auto "
    print(f"  perTrade={label}  {fmt(r['summary'])}")

# === Test 4: requireSubscribe effect (threshold=10%, slippage=0.3%) ===
print("\n" + "=" * 100)
print("TEST 4: Subscribe filter effect (threshold=10%, slippage=0.3%)")
print("=" * 100)
for req_sub in [True, False]:
    params = {**common, 'premiumConfig': {
        'devPct': 10, 'maxSubUnit': 0, 'turnoverWan': 0,
        'excludeQDII': False, 'requireSubscribe': req_sub, 'slippagePct': 0.3
    }}
    r = run_bt(params)
    print(f"  requireSubscribe={str(req_sub):5s}  {fmt(r['summary'])}")

# === Test 5: Turnover filter (threshold=10%, slippage=0.3%) ===
print("\n" + "=" * 100)
print("TEST 5: Turnover filter sweep (threshold=10%, slippage=0.3%)")
print("=" * 100)
for tov in [0, 100, 500, 1000, 2000, 5000]:
    params = {**common, 'premiumConfig': {
        'devPct': 10, 'maxSubUnit': 0, 'turnoverWan': tov,
        'excludeQDII': False, 'requireSubscribe': True, 'slippagePct': 0.3
    }}
    r = run_bt(params)
    print(f"  turnover>={tov:5d}wan  {fmt(r['summary'])}")

# === Test 6: Without codes filter (all LOFs, threshold=10%) - test data coverage ===
print("\n" + "=" * 100)
print("TEST 6: All LOFs (threshold=10%, slippage=0.3%, turnover>=500wan)")
print("=" * 100)
for pct in [5, 8, 10, 15, 20]:
    params = {**common, 'codes': [], 'premiumConfig': {
        'devPct': pct, 'maxSubUnit': 0, 'turnoverWan': 500,
        'excludeQDII': True, 'requireSubscribe': True, 'slippagePct': 0.3
    }}
    r = run_bt(params)
    print(f"  threshold={pct:2d}%  {fmt(r['summary'])}")
    if r.get('message'):
        print(f"    msg: {r['message']}")

# === Test 7: Best candidate - different date ranges for robustness ===
print("\n" + "=" * 100)
print("TEST 7: Robustness check - different date ranges (threshold=10%, slippage=0.3%, 161226)")
print("=" * 100)
ranges = [
    ('2021-05-01', '2022-12-31', '2021H2-2022'),
    ('2023-01-01', '2024-06-30', '2023-2024H1'),
    ('2024-07-01', '2026-04-28', '2024H2-2026'),
    ('2021-05-01', '2023-12-31', '2021-2023'),
    ('2024-01-01', '2026-04-28', '2024-2026'),
    ('2021-05-01', '2026-04-28', 'FULL 5yr'),
]
for sd, ed, label in ranges:
    params = {**common, 'startDate': sd, 'endDate': ed, 'premiumConfig': {
        'devPct': 10, 'maxSubUnit': 0, 'turnoverWan': 0,
        'excludeQDII': False, 'requireSubscribe': True, 'slippagePct': 0.3
    }}
    r = run_bt(params)
    print(f"  {label:15s}  {fmt(r['summary'])}")

# === Test 8: Conservative vs aggressive ===
print("\n" + "=" * 100)
print("TEST 8: Strategy profiles")
print("=" * 100)
profiles = [
    ("Conservative(20%,0.5slip)", 20, 0.5, 5000, True),
    ("Moderate(15%,0.3slip)", 15, 0.3, 10000, True),
    ("Standard(10%,0.3slip)", 10, 0.3, 0, True),
    ("Aggressive(5%,0.1slip)", 5, 0.1, 0, True),
    ("YOLO(3%,0slip)", 3, 0, 0, False),
]
for name, pct, slip, amt, req_sub in profiles:
    params = {**common, 'perTradeAmount': amt, 'premiumConfig': {
        'devPct': pct, 'maxSubUnit': 0, 'turnoverWan': 0,
        'excludeQDII': False, 'requireSubscribe': req_sub, 'slippagePct': slip
    }}
    r = run_bt(params)
    print(f"  {name:30s}  {fmt(r['summary'])}")

print("\nDone.")
