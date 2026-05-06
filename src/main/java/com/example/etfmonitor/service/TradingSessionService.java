package com.example.etfmonitor.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

@Service
public class TradingSessionService {

    private final ZoneId zoneId;
    private final LocalTime morningStart;
    private final LocalTime morningEnd;
    private final LocalTime afternoonStart;
    private final LocalTime afternoonEnd;

    public TradingSessionService(@Value("${app.market.zone-id}") String zoneId,
                                 @Value("${app.market.morning-start}") String morningStart,
                                 @Value("${app.market.morning-end}") String morningEnd,
                                 @Value("${app.market.afternoon-start}") String afternoonStart,
                                 @Value("${app.market.afternoon-end}") String afternoonEnd) {
        this.zoneId = ZoneId.of(zoneId);
        this.morningStart = LocalTime.parse(morningStart);
        this.morningEnd = LocalTime.parse(morningEnd);
        this.afternoonStart = LocalTime.parse(afternoonStart);
        this.afternoonEnd = LocalTime.parse(afternoonEnd);
    }

    public ZonedDateTime now() {
        return ZonedDateTime.now(zoneId);
    }

    public boolean isContinuousTrading() {
        ZonedDateTime now = now();
        if (now.getDayOfWeek() == DayOfWeek.SATURDAY || now.getDayOfWeek() == DayOfWeek.SUNDAY) {
            return false;
        }
        LocalTime localTime = now.toLocalTime();
        return isBetween(localTime, morningStart, morningEnd) || isBetween(localTime, afternoonStart, afternoonEnd);
    }

    public String marketPhase() {
        ZonedDateTime now = now();
        LocalTime localTime = now.toLocalTime();
        if (now.getDayOfWeek() == DayOfWeek.SATURDAY || now.getDayOfWeek() == DayOfWeek.SUNDAY) {
            return "休市";
        }
        if (isBetween(localTime, morningStart, morningEnd) || isBetween(localTime, afternoonStart, afternoonEnd)) {
            return "连续交易";
        }
        if (localTime.isAfter(morningEnd) && localTime.isBefore(afternoonStart)) {
            return "午间休市";
        }
        return "非交易时段";
    }

    public LocalDateTime truncateToMinute(LocalDateTime ts) {
        return ts.withSecond(0).withNano(0);
    }

    private boolean isBetween(LocalTime candidate, LocalTime start, LocalTime end) {
        return !candidate.isBefore(start) && !candidate.isAfter(end);
    }
}
