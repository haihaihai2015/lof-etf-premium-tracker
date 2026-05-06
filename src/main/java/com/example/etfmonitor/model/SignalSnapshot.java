package com.example.etfmonitor.model;

import com.example.etfmonitor.domain.SignalLevel;

import java.time.LocalDateTime;

public record SignalSnapshot(
    String code,
    SignalLevel signalLevel,
    String signalReason,
    LocalDateTime signalTs
) {
}
