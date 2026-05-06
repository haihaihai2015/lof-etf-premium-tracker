"""
Backtest analysis excluding 161226 (国投白银LOF).
Focus on real-world tradeable strategies across all other LOFs.
"""
import urllib.request, json
from collections import defaultdict

BASE = 'http://localhost:8080/api/backtest/run'

EXCLUDE = ['161226']
print(f"Testing all LOFs excluding {EXCLUDE}")

def run_bt(params):
    req = urllib.request.Request(BASE, data=json.dumps(params).encode(),
        headers={'Content-Type': 'application/json'})
    return json.loads(urllib.request.urlopen(req, timeout=300).read())

def fmt(s):
    return (f"trades={s['totalTrades']:4d}  win={s['winRate']:5.1f}%  "
            f"return={s['totalReturnPct']:8.2f}%  maxDD={s['maxDrawdownPct']:5.1f}%  "
            f"avgHold={s['avgHoldDays']:4.1f}d  profit={s['totalReturn']:8.0f}  "
            f"util={s['capitalUtilization']:4.1f}%")

common_fee = {
    'subscribeRate': 0.0012,
    'redeemRate7d': 0.005,
    'redeemRateShort': 0.015,
    'commission': 0.00025
}

def pbt(start, end, pct, slip, amt, req_sub, turnover, excl_qdii):
    return run_bt({
        'startDate': start, 'endDate': end, 'capital': 100000,
        'codes': [], 'excludeCodes': EXCLUDE, 'strategy': 'PREMIUM', 'perTradeAmount': amt,
        'premiumConfig': {
            'devPct': pct, 'maxSubUnit': 0, 'turnoverWan': turnover,
            'excludeQDII': excl_qdii, 'requireSubscribe': req_sub,
            'slippagePct': slip
        },
        'feeConfig': common_fee
    })

S, E = '2021-05-01', '2026-04-28'

# ===================================================================
print("\n" + "=" * 130)
print("1. PREMIUM THRESHOLD SWEEP (excludeQDII=True, requireSubscribe=True, slippage=0.3%, turnover>=500万)")
print("=" * 130)
for pct in [1, 2, 3, 5, 8, 10, 15, 20]:
    r = pbt(S, E, pct, 0.3, 0, True, 500, True)
    print(f"  threshold={pct:2d}%  {fmt(r['summary'])}")

# ===================================================================
print("\n" + "=" * 130)
print("2. THRESHOLD SWEEP (excludeQDII=False, requireSubscribe=True, slippage=0.3%, turnover>=500万)")
print("   -- Include QDII to expand universe --")
print("=" * 130)
for pct in [1, 2, 3, 5, 8, 10, 15, 20]:
    r = pbt(S, E, pct, 0.3, 0, True, 500, False)
    print(f"  threshold={pct:2d}%  {fmt(r['summary'])}")

# ===================================================================
print("\n" + "=" * 130)
print("3. THRESHOLD SWEEP (excludeQDII=False, requireSubscribe=False, slippage=0.3%, turnover>=500万)")
print("   -- Most permissive --")
print("=" * 130)
for pct in [1, 2, 3, 5, 8, 10, 15, 20]:
    r = pbt(S, E, pct, 0.3, 0, False, 500, False)
    print(f"  threshold={pct:2d}%  {fmt(r['summary'])}")

# ===================================================================
print("\n" + "=" * 130)
print("4. TURNOVER FILTER (threshold=5%, slippage=0.3%, excludeQDII=False, requireSubscribe=True)")
print("=" * 130)
for tov in [0, 100, 300, 500, 1000, 2000, 5000]:
    r = pbt(S, E, 5, 0.3, 0, True, tov, False)
    print(f"  turnover>={tov:5d}万  {fmt(r['summary'])}")

# ===================================================================
print("\n" + "=" * 130)
print("5. SLIPPAGE SENSITIVITY (threshold=5%, turnover>=500万, excludeQDII=False, requireSubscribe=True)")
print("=" * 130)
for slip in [0, 0.1, 0.3, 0.5, 1.0, 1.5, 2.0]:
    r = pbt(S, E, 5, slip, 0, True, 500, False)
    print(f"  slippage={slip:.1f}%  {fmt(r['summary'])}")

# ===================================================================
print("\n" + "=" * 130)
print("6. PER-TRADE AMOUNT (threshold=5%, slippage=0.3%, turnover>=500万, excludeQDII=False, requireSub=True)")
print("=" * 130)
for amt in [0, 5000, 10000, 20000, 50000]:
    r = pbt(S, E, 5, 0.3, amt, True, 500, False)
    label = f"{amt:6d}" if amt > 0 else " auto "
    print(f"  perTrade={label}  {fmt(r['summary'])}")

# ===================================================================
print("\n" + "=" * 130)
print("7. ROBUSTNESS: TIME PERIOD TESTS (threshold=5%, slippage=0.3%, turnover>=500万, exclQDII=F, reqSub=T)")
print("=" * 130)
ranges = [
    ('2021-05-01', '2022-06-30', '2021H2-2022H1'),
    ('2022-07-01', '2023-12-31', '2022H2-2023'),
    ('2024-01-01', '2025-06-30', '2024-2025H1'),
    ('2025-07-01', '2026-04-28', '2025H2-2026'),
    ('2021-05-01', '2023-12-31', 'First 2.5yr'),
    ('2024-01-01', '2026-04-28', 'Last 2.3yr'),
    ('2021-05-01', '2026-04-28', 'FULL 5yr'),
]
for sd, ed, label in ranges:
    r = pbt(sd, ed, 5, 0.3, 0, True, 500, False)
    print(f"  {label:15s}  {fmt(r['summary'])}")

# ===================================================================
print("\n" + "=" * 130)
print("8. ROBUSTNESS: TIME PERIOD TESTS (threshold=3%, slippage=0.3%, turnover>=100万, exclQDII=F, reqSub=F)")
print("   -- More aggressive, more permissive --")
print("=" * 130)
for sd, ed, label in ranges:
    r = pbt(sd, ed, 3, 0.3, 0, False, 100, False)
    print(f"  {label:15s}  {fmt(r['summary'])}")

# ===================================================================
print("\n" + "=" * 130)
print("9. STRATEGY PROFILES (FULL 5yr)")
print("=" * 130)
profiles = [
    ("Safe-HighPrem",    15, 0.5, 10000, True,  2000, True),
    ("Safe-HighPrem+Q",  15, 0.5, 10000, True,  2000, False),
    ("Balanced",         10, 0.3, 10000, True,   500, True),
    ("Balanced+QDII",    10, 0.3, 10000, True,   500, False),
    ("Active",            5, 0.3, 10000, True,   500, False),
    ("Active-NoFilter",   5, 0.3, 10000, True,   100, False),
    ("Aggressive",        3, 0.3, 10000, True,   100, False),
    ("Aggressive-Open",   3, 0.3, 10000, False,  100, False),
    ("HighFreq",          2, 0.3,  5000, False,    0, False),
    ("LowThresh-Safe",    2, 0.3,  5000, True,  500, False),
]
for name, pct, slip, amt, req_sub, tov, excl_qdii in profiles:
    r = pbt(S, E, pct, slip, amt, req_sub, tov, excl_qdii)
    print(f"  {name:20s}  {fmt(r['summary'])}")

# ===================================================================
print("\n" + "=" * 130)
print("10. TOP INSTRUMENTS BY TRADE COUNT (threshold=5%, slip=0.3%, tov>=500万, exclQDII=F, reqSub=T)")
print("=" * 130)
r = pbt(S, E, 5, 0.3, 10000, True, 500, False)
by_code = defaultdict(list)
for t in r['trades']:
    by_code[t['code']].append(t)
sorted_codes = sorted(by_code.items(), key=lambda x: sum(t['profit'] for t in x[1]), reverse=True)
print(f"Total: {len(r['trades'])} trades across {len(by_code)} instruments")
print(f"{'code':>8s} {'name':>22s}  {'n':>3s}  {'W/L':>7s}  {'profit':>8s}  {'avgPnL':>7s}  {'avgPrem':>7s}  {'maxLoss':>8s}  {'avgHold':>7s}")
for code, trades in sorted_codes[:40]:
    wins = sum(1 for t in trades if t['profit'] > 0)
    total_profit = sum(t['profit'] for t in trades)
    avg_pnl = total_profit / len(trades)
    avg_prem = sum(t['entryPremium'] for t in trades) / len(trades)
    max_loss = min(t['profit'] for t in trades) if trades else 0
    avg_hold = sum(t['holdDays'] for t in trades) / len(trades)
    print(f"  {code} {trades[0]['name']:>22s}  {len(trades):3d}  {wins}/{len(trades)-wins:>3d}  "
          f"{total_profit:8.0f}  {avg_pnl:7.0f}  {avg_prem:6.1f}%  {max_loss:8.0f}  {avg_hold:5.1f}d")

# ===================================================================
print("\n" + "=" * 130)
print("11. LOSS PATTERN ANALYSIS (threshold=3%, slip=0.3%, tov>=100万, exclQDII=F, reqSub=T)")
print("=" * 130)
r = pbt(S, E, 3, 0.3, 10000, True, 100, False)
losers = [t for t in r['trades'] if t['profit'] < 0]
winners = [t for t in r['trades'] if t['profit'] > 0]
print(f"Winners: {len(winners)}  avg={sum(t['profit'] for t in winners)/max(len(winners),1):.0f}  "
      f"avgPrem={sum(t['entryPremium'] for t in winners)/max(len(winners),1):.1f}%")
print(f"Losers:  {len(losers)}  avg={sum(t['profit'] for t in losers)/max(len(losers),1):.0f}  "
      f"avgPrem={sum(t['entryPremium'] for t in losers)/max(len(losers),1):.1f}%")

# Group losses by date to find systemic events
from collections import Counter
loss_months = Counter()
for t in losers:
    m = t['openDate'][:7]
    loss_months[m] += 1
print("\nLoss concentration by month (top 10):")
for m, cnt in loss_months.most_common(10):
    month_losers = [t for t in losers if t['openDate'][:7] == m]
    total_loss = sum(t['profit'] for t in month_losers)
    print(f"  {m}  count={cnt:3d}  totalLoss={total_loss:8.0f}")

# Individual worst losses
print(f"\nTop 20 worst individual losses:")
losers.sort(key=lambda t: t['profit'])
for t in losers[:20]:
    print(f"  {t['code']} {t['name']:>20s} {t['openDate']} -> {t['closeDate']}  "
          f"prem={t['entryPremium']:5.1f}%  PnL={t['profit']:7.0f} ({t['profitPct']:5.1f}%)  hold={t['holdDays']}d")

# ===================================================================
print("\n" + "=" * 130)
print("12. YEARLY BREAKDOWN (threshold=5%, slip=0.3%, tov>=500万, exclQDII=F, reqSub=T)")
print("=" * 130)
r = pbt(S, E, 5, 0.3, 0, True, 500, False)
by_year = defaultdict(list)
for t in r['trades']:
    by_year[t['openDate'][:4]].append(t)
for yr in sorted(by_year.keys()):
    trades = by_year[yr]
    wins = sum(1 for t in trades if t['profit'] > 0)
    total = sum(t['profit'] for t in trades)
    avg_prem = sum(t['entryPremium'] for t in trades) / len(trades)
    print(f"  {yr}  trades={len(trades):3d}  W/L={wins}/{len(trades)-wins}  "
          f"profit={total:8.0f}  avgPrem={avg_prem:5.1f}%")

print("\n\nDone.")
