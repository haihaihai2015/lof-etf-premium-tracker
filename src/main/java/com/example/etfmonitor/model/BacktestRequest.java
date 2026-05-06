package com.example.etfmonitor.model;

import java.util.List;

public record BacktestRequest(
    String startDate,
    String endDate,
    double capital,
    List<String> codes,
    List<String> excludeCodes,
    String strategy,          // PREMIUM / DISCOUNT / BOTH
    double perTradeAmount,    // fixed per-trade amount, 0 = use 20% of available capital
    PremiumConfig premiumConfig,
    DiscountConfig discountConfig,
    FeeConfig feeConfig
) {
    public record PremiumConfig(
        double devPct,
        double maxSubUnit,
        double turnoverWan,
        boolean excludeQDII,
        boolean requireSubscribe,
        double slippagePct       // exit slippage in %, e.g. 0.5 means sell at close*(1-0.5%)
    ) {}

    public record DiscountConfig(
        double devPct,
        double turnoverWan,
        int minHoldDays,
        boolean indexOnly,
        boolean excludeHighVol,
        boolean requireRedeem
    ) {}

    public record FeeConfig(
        double subscribeRate,
        double redeemRate7d,
        double redeemRateShort,
        double commission
    ) {
        public static FeeConfig defaults() {
            return new FeeConfig(0.0012, 0.005, 0.015, 0.00025);
        }
    }
}
