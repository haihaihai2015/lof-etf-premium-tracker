package com.example.etfmonitor.model;

import java.util.List;
import java.util.Map;

public record FilterMetaResponse(
    Map<String, Object> defaults,
    List<String> tradingSessions,
    List<String> signalLevels,
    List<String> sortFields
) {
}
