import urllib.request, json

BASE = 'http://localhost:8080/api/backtest/run'

def run_bt(params):
    req = urllib.request.Request(BASE,
        data=json.dumps(params).encode(),
        headers={'Content-Type': 'application/json'})
    return json.loads(urllib.request.urlopen(req).read())

common_fee = {
    'subscribeRate': 0.0012,
    'redeemRate7d': 0.005,
    'redeemRateShort': 0.015,
    'commission': 0.00025
}

# === Deep dive: Look at individual trades for 161226 at 5% threshold ===
print("=" * 110)
print("TRADE DETAIL: 161226, threshold=5%, slippage=0.3%, requireSubscribe=True")
print("=" * 110)
r = run_bt({
    'startDate': '2021-05-01', 'endDate': '2026-04-28', 'capital': 100000,
    'codes': ['161226'], 'strategy': 'PREMIUM', 'perTradeAmount': 10000,
    'premiumConfig': {'devPct': 5, 'maxSubUnit': 0, 'turnoverWan': 0,
                      'excludeQDII': False, 'requireSubscribe': True, 'slippagePct': 0.3},
    'feeConfig': common_fee
})
print(f"{'open':>12s} {'close':>12s} {'premium%':>9s} {'entryNav':>9s} {'entryPx':>9s} {'exitPx':>9s} {'profit':>9s} {'pct%':>7s} {'hold':>5s}")
for t in r['trades']:
    print(f"{t['openDate']:>12s} {t['closeDate']:>12s} {t['entryPremium']:>8.1f}% {t['entryNav']:>9.4f} {t['entryPrice']:>9.3f} {t['exitPrice']:>9.3f} {t['profit']:>9.1f} {t['profitPct']:>6.1f}% {t['holdDays']:>4d}d")

# === Check: What premiums does 161226 actually have? ===
print("\n" + "=" * 110)
print("PREMIUM DISTRIBUTION: 161226 (all dates with premium data)")
print("=" * 110)

# We need to query the DB for this - use the backtest API with 0% threshold
r_all = run_bt({
    'startDate': '2021-05-01', 'endDate': '2026-04-28', 'capital': 100000,
    'codes': ['161226'], 'strategy': 'PREMIUM', 'perTradeAmount': 1000,
    'premiumConfig': {'devPct': 0.01, 'maxSubUnit': 0, 'turnoverWan': 0,
                      'excludeQDII': False, 'requireSubscribe': False, 'slippagePct': 0},
    'feeConfig': common_fee
})
premiums = [t['entryPremium'] for t in r_all['trades']]
print(f"Total opportunities with premium > 0%: {len(premiums)}")
if premiums:
    bins = [(0,3),(3,5),(5,8),(8,10),(10,15),(15,20),(20,30),(30,50),(50,100)]
    for lo, hi in bins:
        cnt = sum(1 for p in premiums if lo <= p < hi)
        if cnt > 0:
            avg = sum(p for p in premiums if lo <= p < hi) / cnt
            print(f"  {lo:2d}%-{hi:2d}%: {cnt:3d} trades, avg premium={avg:.1f}%")

# === All LOFs test - see which instruments contributed ===
print("\n" + "=" * 110)
print("ALL LOFs TRADE DETAIL: threshold=5%, slippage=0.3%, requireSubscribe=True")
print("=" * 110)
r_all_lof = run_bt({
    'startDate': '2021-05-01', 'endDate': '2026-04-28', 'capital': 100000,
    'codes': [], 'strategy': 'PREMIUM', 'perTradeAmount': 10000,
    'premiumConfig': {'devPct': 5, 'maxSubUnit': 0, 'turnoverWan': 500,
                      'excludeQDII': True, 'requireSubscribe': True, 'slippagePct': 0.3},
    'feeConfig': common_fee
})
# Group by code
from collections import defaultdict
by_code = defaultdict(list)
for t in r_all_lof['trades']:
    by_code[t['code']].append(t)
for code, trades in sorted(by_code.items()):
    wins = sum(1 for t in trades if t['profit'] > 0)
    total_profit = sum(t['profit'] for t in trades)
    avg_prem = sum(t['entryPremium'] for t in trades) / len(trades)
    print(f"  {code} {trades[0]['name']:20s}  trades={len(trades):3d}  win={wins}/{len(trades)}  profit={total_profit:8.0f}  avgPremium={avg_prem:.1f}%")

# === Exclude 161226, see what's left ===
print("\n" + "=" * 110)
print("ALL LOFs EXCEPT 161226: threshold=5%, slippage=0.3%")
print("=" * 110)
# Can't exclude via API, but let's look at non-161226 trades
non_161226 = [t for t in r_all_lof['trades'] if t['code'] != '161226']
print(f"Non-161226 trades: {len(non_161226)}")
for t in non_161226:
    print(f"  {t['code']} {t['name']:20s} {t['openDate']} -> {t['closeDate']}  premium={t['entryPremium']:.1f}%  profit={t['profit']:.0f} ({t['profitPct']:.1f}%)")

# === Check data coverage - how many instruments have sufficient data ===
print("\n" + "=" * 110)
print("DATA COVERAGE CHECK: Which instruments have enough premium data for backtesting?")
print("=" * 110)
# Run with very low threshold to see which instruments show up
r_coverage = run_bt({
    'startDate': '2021-05-01', 'endDate': '2026-04-28', 'capital': 100000,
    'codes': [], 'strategy': 'PREMIUM', 'perTradeAmount': 1000,
    'premiumConfig': {'devPct': 0.01, 'maxSubUnit': 0, 'turnoverWan': 0,
                      'excludeQDII': False, 'requireSubscribe': False, 'slippagePct': 0},
    'feeConfig': common_fee
})
by_code2 = defaultdict(list)
for t in r_coverage['trades']:
    by_code2[t['code']].append(t)
print(f"Total instruments with any premium signal: {len(by_code2)}")
sorted_codes = sorted(by_code2.items(), key=lambda x: len(x[1]), reverse=True)
for code, trades in sorted_codes[:20]:
    avg_prem = sum(t['entryPremium'] for t in trades) / len(trades)
    print(f"  {code} {trades[0]['name']:25s}  signals={len(trades):4d}  avgPremium={avg_prem:5.1f}%")

print("\nDone.")
