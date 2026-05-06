package com.example.etfmonitor.model;

import java.util.List;

public record InstrumentDetailResponse(
    InstrumentOverview instrument,
    List<TimelinePoint> timeline
) {
}
