package com.example.etfmonitor.service;

import com.example.etfmonitor.entity.DailyBar;
import com.example.etfmonitor.entity.InstrumentInfo;
import com.example.etfmonitor.model.BacktestRequest;
import com.example.etfmonitor.model.BacktestResult;
import com.example.etfmonitor.repository.DailyBarRepository;
import com.example.etfmonitor.repository.InstrumentInfoRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class BacktestEngine {

    private static final Logger log = LoggerFactory.getLogger(BacktestEngine.class);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private static final Set<String> HIGH_VOL_KEYWORDS = Set.of(
        "半导体", "芯片", "白酒", "军工", "新能源", "光伏", "锂电", "生物医药",
        "有色", "煤炭", "钢铁", "证券"
    );
    private static final Set<String> QDII_KEYWORDS = Set.of(
        "QDII", "纳斯达克", "标普", "美国", "日经", "恒生", "港股", "海外",
        "全球", "亚太", "德国", "法国", "印度", "越南"
    );

    private final DailyBarRepository dailyBarRepository;
    private final InstrumentInfoRepository instrumentInfoRepository;

    public BacktestEngine(DailyBarRepository dailyBarRepository,
                          InstrumentInfoRepository instrumentInfoRepository) {
        this.dailyBarRepository = dailyBarRepository;
        this.instrumentInfoRepository = instrumentInfoRepository;
    }

    public BacktestResult run(BacktestRequest request) {
        LocalDate startDate = LocalDate.parse(request.startDate(), DATE_FMT);
        LocalDate endDate = LocalDate.parse(request.endDate(), DATE_FMT);
        double capital = request.capital();

        BacktestRequest.FeeConfig fees = request.feeConfig() != null
            ? request.feeConfig()
            : BacktestRequest.FeeConfig.defaults();

        // Determine which instruments to backtest (LOF only)
        List<InstrumentInfo> instruments;
        if (request.codes() != null && !request.codes().isEmpty()) {
            Set<String> codeSet = new HashSet<>(request.codes());
            instruments = instrumentInfoRepository.findAll().stream()
                .filter(i -> i.getInstrumentType() == com.example.etfmonitor.domain.InstrumentType.LOF)
                .filter(i -> codeSet.contains(i.getCode()))
                .collect(Collectors.toList());
        } else {
            instruments = instrumentInfoRepository.findAll().stream()
                .filter(i -> i.getInstrumentType() == com.example.etfmonitor.domain.InstrumentType.LOF)
                .collect(Collectors.toList());
        }
        // Apply exclusion filter
        if (request.excludeCodes() != null && !request.excludeCodes().isEmpty()) {
            Set<String> excludeSet = new HashSet<>(request.excludeCodes());
            instruments = instruments.stream()
                .filter(i -> !excludeSet.contains(i.getCode()))
                .collect(Collectors.toList());
        }

        // Build instrument info map
        Map<String, InstrumentInfo> infoMap = instruments.stream()
            .collect(Collectors.toMap(InstrumentInfo::getCode, i -> i, (a, b) -> a));

        // Load daily bars only for instruments that have premium_rate data (skip empty ones)
        Map<String, List<DailyBar>> barsByCode = new HashMap<>();
        for (InstrumentInfo inst : instruments) {
            List<DailyBar> bars = dailyBarRepository
                .findByCodeAndTradeDateBetweenOrderByTradeDateAsc(inst.getCode(), startDate, endDate);
            if (bars.isEmpty()) continue;
            // Skip instruments with zero premium_rate data (no arbitrage signals possible)
            boolean hasAnyPremium = bars.stream().anyMatch(b -> b.getPremiumRate() != null);
            if (hasAnyPremium) {
                barsByCode.put(inst.getCode(), bars);
            }
        }
        log.info("Backtest loaded {} instruments with premium data out of {} LOFs",
            barsByCode.size(), instruments.size());

        // Build trade date list (union of all dates)
        List<LocalDate> tradeDates = dailyBarRepository.findDistinctTradeDates(startDate, endDate);
        if (tradeDates.isEmpty()) {
            return emptyResult(capital);
        }

        // Build date-indexed lookup: code -> date -> bar
        Map<String, Map<LocalDate, DailyBar>> barIndex = new HashMap<>();
        for (var entry : barsByCode.entrySet()) {
            Map<LocalDate, DailyBar> dateMap = new HashMap<>();
            for (DailyBar bar : entry.getValue()) {
                dateMap.put(bar.getTradeDate(), bar);
            }
            barIndex.put(entry.getKey(), dateMap);
        }

        // Run simulation
        double equity = capital;
        double peakEquity = capital;
        double maxDrawdown = 0;
        double maxDrawdownPct = 0;
        double totalFees = 0;
        double totalLockedCapital = 0;
        int capitalDays = 0;

        List<BacktestResult.TradeRecord> trades = new ArrayList<>();
        List<BacktestResult.EquityPoint> equityCurve = new ArrayList<>();
        List<PendingTrade> pendingTrades = new ArrayList<>();
        Set<String> pendingCodes = new HashSet<>();   // P0-2: prevent duplicate positions

        boolean doPremium = "PREMIUM".equals(request.strategy()) || "BOTH".equals(request.strategy());
        boolean doDiscount = "DISCOUNT".equals(request.strategy()) || "BOTH".equals(request.strategy());

        for (int di = 0; di < tradeDates.size(); di++) {
            LocalDate today = tradeDates.get(di);

            // 1. Close matured trades
            List<PendingTrade> stillPending = new ArrayList<>();
            for (PendingTrade pt : pendingTrades) {
                if (!today.isBefore(pt.exitDate)) {
                    // Close this trade
                    Map<LocalDate, DailyBar> dateMap = barIndex.get(pt.code);
                    DailyBar exitBar = dateMap != null ? dateMap.get(pt.exitDate) : null;
                    if (exitBar == null || exitBar.getClosePrice() == null) {
                        // Try next available date
                        exitBar = findNextBar(barIndex.get(pt.code), pt.exitDate, tradeDates, di);
                    }

                    double exitPrice;
                    LocalDate actualExitDate;
                    if (exitBar != null && exitBar.getClosePrice() != null) {
                        exitPrice = exitBar.getClosePrice();
                        actualExitDate = exitBar.getTradeDate();
                    } else {
                        // Fallback: use entry price (no profit/loss)
                        exitPrice = pt.entryPrice;
                        actualExitDate = pt.exitDate;
                    }

                    double exitFee;
                    double proceeds;
                    pendingCodes.remove(pt.code);  // P0-2: free up slot
                    if ("PREMIUM".equals(pt.direction)) {
                        // P1-1: apply slippage to sell price
                        double slippage = request.premiumConfig() != null
                            ? request.premiumConfig().slippagePct() / 100.0 : 0;
                        double slippedPrice = exitPrice * (1 - slippage);
                        proceeds = pt.shares * slippedPrice;
                        exitFee = proceeds * fees.commission();
                    } else {
                        // Redeem at NAV: redeem fee
                        DailyBar exitBarForNav = exitBar;
                        double redeemNav = (exitBarForNav != null && exitBarForNav.getNav() != null)
                            ? exitBarForNav.getNav() : exitPrice;
                        proceeds = pt.shares * redeemNav;
                        int holdDays = (int) (actualExitDate.toEpochDay() - pt.entryDate.toEpochDay());
                        double redeemRate = holdDays >= 7 ? fees.redeemRate7d() : fees.redeemRateShort();
                        exitFee = proceeds * redeemRate;
                    }

                    double netProceeds = proceeds - exitFee;
                    double profit = netProceeds - pt.cost;
                    double profitPct = pt.cost > 0 ? profit / pt.cost * 100 : 0;
                    int holdDays = (int) (actualExitDate.toEpochDay() - pt.entryDate.toEpochDay());

                    equity += netProceeds;
                    totalFees += exitFee;

                    InstrumentInfo info = infoMap.get(pt.code);
                    trades.add(new BacktestResult.TradeRecord(
                        pt.code,
                        info != null ? info.getName() : pt.code,
                        pt.direction,
                        pt.entryDate.format(DATE_FMT),
                        actualExitDate.format(DATE_FMT),
                        pt.entryPremium * 100,
                        pt.entryNav,
                        pt.entryPrice,
                        exitPrice,
                        pt.cost,
                        Math.round(profit * 100.0) / 100.0,
                        Math.round(profitPct * 100.0) / 100.0,
                        Math.round((pt.entryFee + exitFee) * 100.0) / 100.0,
                        holdDays
                    ));
                } else {
                    stillPending.add(pt);
                }
            }
            pendingTrades = stillPending;

            // Track capital utilization
            double lockedNow = pendingTrades.stream().mapToDouble(p -> p.cost).sum();
            totalLockedCapital += lockedNow;
            capitalDays++;

            // 2. Scan for new opportunities
            double availableCapital = equity - lockedNow;

            for (var entry : barIndex.entrySet()) {
                String code = entry.getKey();
                DailyBar bar = entry.getValue().get(today);
                if (bar == null || bar.getPremiumRate() == null || bar.getClosePrice() == null) continue;

                InstrumentInfo info = infoMap.get(code);
                if (info == null) continue;

                double premiumRate = bar.getPremiumRate();

                // Premium arbitrage: premium_rate >= threshold
                if (doPremium && request.premiumConfig() != null) {
                    BacktestRequest.PremiumConfig pc = request.premiumConfig();
                    if (premiumRate >= pc.devPct() / 100.0) {
                        if (pendingCodes.contains(code)) continue;  // P0-2: skip duplicate
                        if (passesFilters(info, bar, pc, availableCapital)) {
                            // T+2 exit date: subscribe T, shares arrive T+2, sell immediately
                            LocalDate exitDate = findTradeDateOffset(tradeDates, di, 2);
                            if (exitDate == null) continue;

                            // Subscribe at NAV
                            double nav = bar.getNav() != null ? bar.getNav() : bar.getClosePrice();
                            // P2-1: configurable per-trade amount
                            double maxAmount = request.perTradeAmount() > 0
                                ? Math.min(request.perTradeAmount(), availableCapital)
                                : Math.min(availableCapital * 0.2, availableCapital);
                            if (pc.maxSubUnit() > 0) {
                                maxAmount = Math.min(maxAmount, pc.maxSubUnit());
                            }
                            if (maxAmount < 100) continue;

                            double entryFee = maxAmount * fees.subscribeRate();
                            double netAmount = maxAmount - entryFee;
                            double shares = netAmount / nav;

                            equity -= maxAmount;
                            totalFees += entryFee;
                            availableCapital -= maxAmount;
                            pendingCodes.add(code);  // P0-2

                            pendingTrades.add(new PendingTrade(
                                code, "PREMIUM", today, exitDate,
                                bar.getClosePrice(), nav, shares,
                                maxAmount, entryFee, premiumRate
                            ));
                        }
                    }
                }

                // Discount arbitrage: premium_rate <= -threshold
                if (doDiscount && request.discountConfig() != null) {
                    BacktestRequest.DiscountConfig dc = request.discountConfig();
                    if (premiumRate <= -(dc.devPct() / 100.0)) {
                        if (pendingCodes.contains(code)) continue;  // P0-2: skip duplicate
                        if (passesDiscountFilters(info, bar, dc)) {
                            // Exit after minHoldDays trading days
                            int holdDays = Math.max(dc.minHoldDays(), 7);
                            LocalDate exitDate = findTradeDateOffset(tradeDates, di, holdDays);
                            if (exitDate == null) continue;

                            // P2-1: configurable per-trade amount
                            double maxAmount = request.perTradeAmount() > 0
                                ? Math.min(request.perTradeAmount(), availableCapital)
                                : Math.min(availableCapital * 0.2, availableCapital);
                            if (maxAmount < 100) continue;

                            double buyPrice = bar.getClosePrice();
                            double entryFee = maxAmount * fees.commission();
                            double netAmount = maxAmount - entryFee;
                            double shares = netAmount / buyPrice;

                            equity -= maxAmount;
                            totalFees += entryFee;
                            availableCapital -= maxAmount;
                            pendingCodes.add(code);  // P0-2

                            pendingTrades.add(new PendingTrade(
                                code, "DISCOUNT", today, exitDate,
                                buyPrice, bar.getNav() != null ? bar.getNav() : buyPrice,
                                shares, maxAmount, entryFee, premiumRate
                            ));
                        }
                    }
                }
            }

            // 3. Record equity curve (P1-2: mark-to-market)
            double pendingValue = 0;
            for (PendingTrade pt : pendingTrades) {
                Map<LocalDate, DailyBar> ptDateMap = barIndex.get(pt.code);
                DailyBar ptBar = ptDateMap != null ? ptDateMap.get(today) : null;
                if ("PREMIUM".equals(pt.direction)) {
                    // Pending subscribe: shares valued at today's market price
                    double mktPrice = (ptBar != null && ptBar.getClosePrice() != null)
                        ? ptBar.getClosePrice() : pt.entryPrice;
                    pendingValue += pt.shares * mktPrice;
                } else {
                    // Pending discount: shares valued at today's NAV
                    double mktNav = (ptBar != null && ptBar.getNav() != null)
                        ? ptBar.getNav() : pt.entryNav;
                    pendingValue += pt.shares * mktNav;
                }
            }
            double totalEquity = equity + pendingValue;
            equityCurve.add(new BacktestResult.EquityPoint(today.format(DATE_FMT), Math.round(totalEquity * 100.0) / 100.0));

            if (totalEquity > peakEquity) peakEquity = totalEquity;
            double dd = peakEquity - totalEquity;
            if (dd > maxDrawdown) {
                maxDrawdown = dd;
                maxDrawdownPct = peakEquity > 0 ? dd / peakEquity * 100 : 0;
            }
        }

        // P1-3: Close remaining pending trades at last market price (not just cost)
        LocalDate lastDate = tradeDates.get(tradeDates.size() - 1);
        for (PendingTrade pt : pendingTrades) {
            Map<LocalDate, DailyBar> ptDateMap = barIndex.get(pt.code);
            DailyBar lastBar = ptDateMap != null ? ptDateMap.get(lastDate) : null;
            double closePrice;
            if ("PREMIUM".equals(pt.direction)) {
                closePrice = (lastBar != null && lastBar.getClosePrice() != null)
                    ? lastBar.getClosePrice() : pt.entryPrice;
                double proceeds = pt.shares * closePrice;
                double exitFee = proceeds * fees.commission();
                equity += proceeds - exitFee;
                totalFees += exitFee;
            } else {
                double nav = (lastBar != null && lastBar.getNav() != null)
                    ? lastBar.getNav() : pt.entryNav;
                double proceeds = pt.shares * nav;
                int holdDays = (int) (lastDate.toEpochDay() - pt.entryDate.toEpochDay());
                double redeemRate = holdDays >= 7 ? fees.redeemRate7d() : fees.redeemRateShort();
                double exitFee = proceeds * redeemRate;
                equity += proceeds - exitFee;
                totalFees += exitFee;
            }
        }

        double finalEquity = equity;
        double totalReturn = finalEquity - capital;
        double totalReturnPct = capital > 0 ? totalReturn / capital * 100 : 0;
        int winCount = (int) trades.stream().filter(t -> t.profit() > 0).count();
        int lossCount = (int) trades.stream().filter(t -> t.profit() <= 0).count();
        double winRate = trades.isEmpty() ? 0 : (double) winCount / trades.size() * 100;
        double maxProfit = trades.stream().mapToDouble(BacktestResult.TradeRecord::profit).max().orElse(0);
        double maxLoss = trades.stream().mapToDouble(BacktestResult.TradeRecord::profit).min().orElse(0);
        double avgHold = trades.stream().mapToInt(BacktestResult.TradeRecord::holdDays).average().orElse(0);
        double avgUtilization = capitalDays > 0 ? totalLockedCapital / capitalDays / capital * 100 : 0;

        BacktestResult.Summary summary = new BacktestResult.Summary(
            Math.round(totalReturn * 100.0) / 100.0,
            Math.round(totalReturnPct * 100.0) / 100.0,
            trades.size(),
            winCount,
            lossCount,
            Math.round(winRate * 10.0) / 10.0,
            Math.round(maxProfit * 100.0) / 100.0,
            Math.round(maxLoss * 100.0) / 100.0,
            Math.round(maxDrawdown * 100.0) / 100.0,
            Math.round(maxDrawdownPct * 100.0) / 100.0,
            Math.round(avgHold * 10.0) / 10.0,
            Math.round(totalFees * 100.0) / 100.0,
            Math.round(avgUtilization * 10.0) / 10.0
        );

        String message = null;
        if (trades.isEmpty()) {
            if (barsByCode.isEmpty()) {
                message = "所选日期范围内没有包含溢价率数据的标的。请先同步历史数据，或调整日期范围。";
            } else {
                message = String.format("共扫描 %d 只标的、%d 个交易日，未找到满足条件的套利机会。"
                    + "建议：降低溢价/折价阈值、放宽成交额要求、或取消限购限制。",
                    barsByCode.size(), tradeDates.size());
            }
        }

        return new BacktestResult(summary, equityCurve, trades, message);
    }

    private boolean passesFilters(InstrumentInfo info, DailyBar bar,
                                   BacktestRequest.PremiumConfig pc, double availableCapital) {
        if (bar.getTurnover() != null && bar.getTurnover() < pc.turnoverWan() * 10000L) return false;
        if (pc.maxSubUnit() > 0 && info.getMinimumSubscriptionUnit() != null
            && info.getMinimumSubscriptionUnit() > pc.maxSubUnit()) return false;
        if (pc.excludeQDII() && isQDII(info)) return false;
        if (pc.requireSubscribe()) {
            Boolean canSubscribe = bar.getCanSubscribe();
            if (canSubscribe == null || !canSubscribe) return false;
        }
        return true;
    }

    private boolean passesDiscountFilters(InstrumentInfo info, DailyBar bar,
                                           BacktestRequest.DiscountConfig dc) {
        if (bar.getTurnover() != null && bar.getTurnover() < dc.turnoverWan() * 10000L) return false;
        if (dc.indexOnly() && (info.getIndexName() == null || info.getIndexName().isBlank())) return false;
        if (dc.excludeHighVol() && isHighVol(info)) return false;
        if (dc.requireRedeem()) {
            Boolean canRedeem = bar.getCanRedeem();
            if (canRedeem == null || !canRedeem) return false;
        }
        return true;
    }

    private boolean isQDII(InstrumentInfo info) {
        String name = info.getName();
        String fundType = info.getFundType();
        for (String kw : QDII_KEYWORDS) {
            if ((name != null && name.contains(kw)) || (fundType != null && fundType.contains(kw))) {
                return true;
            }
        }
        return false;
    }

    private boolean isHighVol(InstrumentInfo info) {
        String name = info.getName();
        for (String kw : HIGH_VOL_KEYWORDS) {
            if (name != null && name.contains(kw)) return true;
        }
        return false;
    }

    private LocalDate findTradeDateOffset(List<LocalDate> tradeDates, int currentIndex, int offset) {
        int target = currentIndex + offset;
        return target < tradeDates.size() ? tradeDates.get(target) : null;
    }

    private DailyBar findNextBar(Map<LocalDate, DailyBar> dateMap, LocalDate target,
                                  List<LocalDate> tradeDates, int currentIndex) {
        if (dateMap == null) return null;
        for (int i = currentIndex; i < Math.min(currentIndex + 5, tradeDates.size()); i++) {
            DailyBar bar = dateMap.get(tradeDates.get(i));
            if (bar != null && bar.getClosePrice() != null) return bar;
        }
        return null;
    }

    private BacktestResult emptyResult(double capital) {
        return new BacktestResult(
            new BacktestResult.Summary(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
            List.of(new BacktestResult.EquityPoint(LocalDate.now().format(DATE_FMT), capital)),
            List.of(),
            "所选日期范围内无K线数据，请先同步历史数据。"
        );
    }

    private static class PendingTrade {
        final String code;
        final String direction;
        final LocalDate entryDate;
        final LocalDate exitDate;
        final double entryPrice;
        final double entryNav;
        final double shares;
        final double cost;
        final double entryFee;
        final double entryPremium;

        PendingTrade(String code, String direction, LocalDate entryDate, LocalDate exitDate,
                     double entryPrice, double entryNav, double shares, double cost,
                     double entryFee, double entryPremium) {
            this.code = code;
            this.direction = direction;
            this.entryDate = entryDate;
            this.exitDate = exitDate;
            this.entryPrice = entryPrice;
            this.entryNav = entryNav;
            this.shares = shares;
            this.cost = cost;
            this.entryFee = entryFee;
            this.entryPremium = entryPremium;
        }
    }
}
