package com.example.etfmonitor.model;

import com.example.etfmonitor.domain.MarketSource;

import java.time.LocalDateTime;

public record QuoteSnapshot(
    String code,
    double price,
    Double bid1,
    Double ask1,
    long turnover,
    double changeRate,
    LocalDateTime quoteTs,
    LocalDateTime recvTs,
    MarketSource source,
    boolean stale
) {
}
