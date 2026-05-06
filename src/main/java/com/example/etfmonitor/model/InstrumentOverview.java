package com.example.etfmonitor.model;

import com.example.etfmonitor.domain.InstrumentStatus;
import com.example.etfmonitor.domain.InstrumentType;
import com.example.etfmonitor.domain.SignalLevel;
import com.example.etfmonitor.domain.ValuationType;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record InstrumentOverview(
    String code,
    String name,
    InstrumentType instrumentType,
    String market,
    String fundCompany,
    String indexName,
    Long minimumSubscriptionUnit,
    Long dailyPurchaseLimit,
    String fundType,
    InstrumentStatus status,
    boolean canSubscribe,
    boolean canRedeem,
    double price,
    Double bid1,
    Double ask1,
    long turnover,
    double changeRate,
    ValuationType valuationType,
    Double valuationValue,
    LocalDate valuationDate,
    Double premiumRate,
    Double navRefPremiumRate,
    Double spreadRate,
    SignalLevel signalLevel,
    String signalReason,
    LocalDateTime quoteTs,
    LocalDateTime valuationTs,
    boolean stale,
    String marketPhase,
    String source
) {
}
