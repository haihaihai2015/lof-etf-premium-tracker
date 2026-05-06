package com.example.etfmonitor.model;

import com.example.etfmonitor.domain.ValuationType;

import java.time.LocalDateTime;

public record TimelinePoint(
    LocalDateTime ts,
    double price,
    long turnover,
    Double changeRate,
    Double valuationValue,
    ValuationType valuationType,
    Double premiumRate,
    Double navRefPremiumRate,
    Double spreadRate
) {
}
