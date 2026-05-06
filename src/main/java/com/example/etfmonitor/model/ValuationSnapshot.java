package com.example.etfmonitor.model;

import com.example.etfmonitor.domain.ValuationType;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record ValuationSnapshot(
    String code,
    ValuationType valuationType,
    Double valuationValue,
    LocalDateTime valuationTs,
    LocalDate navDate,
    Double premiumRate,
    Double navRefPremiumRate,
    Double spreadRate
) {
}
