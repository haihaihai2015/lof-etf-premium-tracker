package com.example.etfmonitor.model;

import java.util.List;

public record BacktestResult(
    Summary summary,
    List<EquityPoint> equityCurve,
    List<TradeRecord> trades,
    String message
) {
    public record Summary(
        double totalReturn,
        double totalReturnPct,
        int totalTrades,
        int winTrades,
        int lossTrades,
        double winRate,
        double maxSingleProfit,
        double maxSingleLoss,
        double maxDrawdown,
        double maxDrawdownPct,
        double avgHoldDays,
        double totalFees,
        double capitalUtilization
    ) {}

    public record EquityPoint(
        String date,
        double equity
    ) {}

    public record TradeRecord(
        String code,
        String name,
        String direction,
        String openDate,
        String closeDate,
        double entryPremium,
        double entryNav,
        double entryPrice,
        double exitPrice,
        double amount,
        double profit,
        double profitPct,
        double fees,
        int holdDays
    ) {}
}
