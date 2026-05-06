"""
Full backtest analysis across all LOFs with complete data.
Run this AFTER full historical data sync is complete.
"""
import urllib.request, json, sys, time
from collections import defaultdict

BASE = 'http://localhost:8080/api/backtest/run'

def run_bt(params):
    req = urllib.request.Request(BASE,
        data=json.dumps(params).encode(),
        headers={'Content-Type': 'application/json'})
    resp = urllib.request.urlopen(req, timeout=120)
    return json.loads(resp.read())

def fmt(s):
    return (f"trades={s['totalTrades']:4d}  win={s['winRate']:5.1f}%  "
            f"return={s['totalReturnPct']:8.1f}%  maxDD={s['maxDrawdownPct']:5.1f}%  "
            f"avgHold={s['avgHoldDays']:4.1f}d  fees={s['totalFees']:8.0f}  "
            f"util={s['capitalUtilization']:4.1f}%")

common_fee = {
    'subscribeRate': 0.0012,
    'redeemRate7d': 0.005,
    'redeemRateShort': 0.015,
    'commission': 0.00025
}

def premium_bt(start, end, codes, pct, slip, amt, req_sub, turnover, excl_qdii):
    return run_bt({
        'startDate': start, 'endDate': end, 'capital': 100000,
        'codes': codes, 'strategy': 'PREMIUM', 'perTradeAmount': amt,
        'premiumConfig': {
            'devPct': pct, 'maxSubUnit': 0, 'turnoverWan': turnover,
            'excludeQDII': excl_qdii, 'requireSubscribe': req_sub,
            'slippagePct': slip
        },
        'feeConfig': common_fee
    })

# ===================================================================
print("=" * 120)
print("PHASE 1: PREMIUM THRESHOLD SWEEP (all LOFs, excludeQDII, requireSubscribe, slippage=0.3%)")
print("=" * 120)
for pct in [2, 3, 5, 8, 10, 15, 20, 25, 30]:
    r = premium_bt('2021-05-01', '2026-04-28', [], pct, 0.3, 0, True, 500, True)
    print(f"  threshold={pct:2d}%  {fmt(r['summary'])}")

# ===================================================================
print("\n" + "=" * 120)
print("PHASE 2: SLIPPAGE SENSITIVITY (all LOFs, threshold=10%)")
print("=" * 120)
for slip in [0, 0.1, 0.3, 0.5, 1.0, 2.0, 3.0]:
    r = premium_bt('2021-05-01', '2026-04-28', [], 10, slip, 0, True, 500, True)
    print(f"  slippage={slip:.1f}%  {fmt(r['summary'])}")

# ===================================================================
print("\n" + "=" * 120)
print("PHASE 3: TURNOVER FILTER (all LOFs, threshold=10%, slippage=0.3%)")
print("=" * 120)
for tov in [0, 100, 300, 500, 1000, 2000, 5000]:
    r = premium_bt('2021-05-01', '2026-04-28', [], 10, 0.3, 0, True, tov, True)
    print(f"  turnover>={tov:5d}wan  {fmt(r['summary'])}")

# ===================================================================
print("\n" + "=" * 120)
print("PHASE 4: PER-TRADE AMOUNT (all LOFs, threshold=10%, slippage=0.3%)")
print("=" * 120)
for amt in [0, 1000, 5000, 10000, 20000, 50000]:
    r = premium_bt('2021-05-01', '2026-04-28', [], 10, 0.3, amt, True, 500, True)
    label = f"{amt:6d}" if amt > 0 else " auto "
    print(f"  perTrade={label}  {fmt(r['summary'])}")

# ===================================================================
print("\n" + "=" * 120)
print("PHASE 5: SUBSCRIBE FILTER EFFECT (threshold=10%)")
print("=" * 120)
for req_sub in [True, False]:
    r = premium_bt('2021-05-01', '2026-04-28', [], 10, 0.3, 0, req_sub, 500, True)
    print(f"  requireSub={str(req_sub):5s}  {fmt(r['summary'])}")

# ===================================================================
print("\n" + "=" * 120)
print("PHASE 6: EXCLUDE QDII EFFECT (threshold=10%)")
print("=" * 120)
for eq in [True, False]:
    r = premium_bt('2021-05-01', '2026-04-28', [], 10, 0.3, 0, True, 500, eq)
    print(f"  excludeQDII={str(eq):5s}  {fmt(r['summary'])}")

# ===================================================================
print("\n" + "=" * 120)
print("PHASE 7: ROBUSTNESS - DIFFERENT TIME PERIODS (threshold=10%, slippage=0.3%)")
print("=" * 120)
ranges = [
    ('2021-05-01', '2022-06-30', '2021H2-2022H1'),
    ('2022-07-01', '2023-12-31', '2022H2-2023'),
    ('2024-01-01', '2025-06-30', '2024-2025H1'),
    ('2025-07-01', '2026-04-28', '2025H2-2026'),
    ('2021-05-01', '2023-12-31', 'First half'),
    ('2024-01-01', '2026-04-28', 'Second half'),
    ('2021-05-01', '2026-04-28', 'FULL 5yr'),
]
for sd, ed, label in ranges:
    r = premium_bt(sd, ed, [], 10, 0.3, 0, True, 500, True)
    print(f"  {label:15s}  {fmt(r['summary'])}")

# ===================================================================
print("\n" + "=" * 120)
print("PHASE 8: TRADE DETAIL - TOP INSTRUMENTS BY TRADE COUNT")
print("=" * 120)
r = premium_bt('2021-05-01', '2026-04-28', [], 5, 0.3, 10000, True, 500, True)
by_code = defaultdict(list)
for t in r['trades']:
    by_code[t['code']].append(t)
sorted_codes = sorted(by_code.items(), key=lambda x: len(x[1]), reverse=True)
print(f"Total trades: {len(r['trades'])}, Instruments with trades: {len(by_code)}")
for code, trades in sorted_codes[:30]:
    wins = sum(1 for t in trades if t['profit'] > 0)
    losses = len(trades) - wins
    total_profit = sum(t['profit'] for t in trades)
    avg_prem = sum(t['entryPremium'] for t in trades) / len(trades)
    avg_pnl = total_profit / len(trades)
    max_loss = min(t['profit'] for t in trades) if trades else 0
    print(f"  {code} {trades[0]['name']:20s}  n={len(trades):3d}  W/L={wins}/{losses}  "
          f"profit={total_profit:8.0f}  avgPnL={avg_pnl:7.0f}  avgPrem={avg_prem:5.1f}%  "
          f"maxLoss={max_loss:7.0f}")

# ===================================================================
print("\n" + "=" * 120)
print("PHASE 9: STRATEGY PROFILES - RECOMMENDED CONFIGS")
print("=" * 120)
profiles = [
    ("Ultra-Conservative", 20, 0.5, 5000, True, 1000, True),
    ("Conservative",       15, 0.3, 10000, True, 500, True),
    ("Balanced",           10, 0.3, 0, True, 500, True),
    ("Balanced-NoQDII",    10, 0.3, 0, True, 500, False),
    ("Moderate",            8, 0.3, 0, True, 300, True),
    ("Aggressive",          5, 0.1, 0, True, 300, True),
    ("YOLO",                3, 0, 0, False, 0, False),
]
for name, pct, slip, amt, req_sub, tov, excl_qdii in profiles:
    r = premium_bt('2021-05-01', '2026-04-28', [], pct, slip, amt, req_sub, tov, excl_qdii)
    print(f"  {name:22s}  {fmt(r['summary'])}")

# ===================================================================
print("\n" + "=" * 120)
print("PHASE 10: BEST CONFIG ROBUSTNESS CHECK")
print("=" * 120)
# Re-run balanced config across different time windows
for sd, ed, label in ranges:
    r = premium_bt(sd, ed, [], 10, 0.3, 0, True, 500, True)
    s = r['summary']
    wr = s['winRate']
    tr = s['totalTrades']
    ret = s['totalReturnPct']
    print(f"  {label:15s}  trades={tr:4d}  winRate={wr:5.1f}%  return={ret:8.1f}%  maxDD={s['maxDrawdownPct']:5.1f}%")

# ===================================================================
print("\n" + "=" * 120)
print("PHASE 11: LOSS ANALYSIS - EXAMINE LOSING TRADES")
print("=" * 120)
r = premium_bt('2021-05-01', '2026-04-28', [], 5, 0.3, 10000, True, 0, True)
losers = [t for t in r['trades'] if t['profit'] < 0]
print(f"Total trades: {len(r['trades'])}, Losers: {len(losers)}")
losers.sort(key=lambda t: t['profit'])
for t in losers[:20]:
    print(f"  {t['code']} {t['name']:20s} {t['openDate']} -> {t['closeDate']}  "
          f"prem={t['entryPremium']:5.1f}%  PnL={t['profit']:8.0f} ({t['profitPct']:5.1f}%)  hold={t['holdDays']}d")

print("\n\nAnalysis complete.")
