package com.example.etfmonitor.model;

import java.util.List;

public record InstrumentListResponse(
    List<InstrumentOverview> items,
    long total,
    int page,
    int pageSize
) {
}
