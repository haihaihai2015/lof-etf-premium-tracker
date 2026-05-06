package com.example.etfmonitor.model;

public record RefreshResponse(
    String scope,
    int instrumentCount,
    String message
) {
}
