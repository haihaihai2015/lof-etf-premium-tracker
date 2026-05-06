package com.example.etfmonitor.service;

import com.example.etfmonitor.domain.ValuationType;
import com.example.etfmonitor.entity.InstrumentInfo;
import com.example.etfmonitor.model.InstrumentOverview;
import com.example.etfmonitor.model.TimelinePoint;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Service
public class EastMoneyTimelineService {

    private static final DateTimeFormatter TS_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;
    private final String providerMode;
    private final String trendUrl;

    public EastMoneyTimelineService(ObjectMapper objectMapper,
                                    RestTemplateBuilder restTemplateBuilder,
                                    @Value("${app.market.provider-mode:real}") String providerMode,
                                    @Value("${app.market.eastmoney.trend-url}") String trendUrl,
                                    @Value("${app.market.eastmoney.timeout-ms}") long timeoutMs) {
        this.objectMapper = objectMapper;
        this.providerMode = providerMode;
        this.trendUrl = trendUrl;
        this.restTemplate = restTemplateBuilder
            .setConnectTimeout(Duration.ofMillis(timeoutMs))
            .setReadTimeout(Duration.ofMillis(timeoutMs))
            .build();
    }

    public List<TimelinePoint> fetchTimeline(InstrumentInfo instrument, InstrumentOverview overview) {
        if (!"real".equalsIgnoreCase(providerMode)) {
            return List.of();
        }
        JsonNode root = readJson(UriComponentsBuilder.fromUriString(trendUrl)
            .queryParam("secid", secId(instrument))
            .queryParam("fields1", "f1,f2,f3,f4,f5,f6,f7,f8")
            .queryParam("fields2", "f51,f52,f53,f54,f55,f56,f57,f58")
            .queryParam("iscr", "0")
            .queryParam("ndays", "1")
            .build(true)
            .toUri());
        if (root == null || root.path("rc").asInt(-1) != 0) {
            return List.of();
        }

        JsonNode data = root.path("data");
        JsonNode trends = data.path("trends");
        if (!trends.isArray() || trends.isEmpty()) {
            return List.of();
        }

        double preClose = data.path("preClose").asDouble(0.0);
        List<TimelinePoint> points = new ArrayList<>();
        for (JsonNode trendNode : trends) {
            String raw = trendNode.asText();
            String[] parts = raw.split(",");
            if (parts.length < 7) {
                continue;
            }

            LocalDateTime ts = LocalDateTime.parse(parts[0], TS_FORMATTER);
            double price = parseDouble(parts[2]);
            long turnover = Math.round(parseDouble(parts[6]));
            Double changeRate = preClose > 0 ? roundRate((price - preClose) / preClose) : null;
            Double valuationValue = overview.valuationValue();
            Double premiumRate = null;
            Double navRefPremiumRate = null;
            if (valuationValue != null && valuationValue > 0) {
                if (overview.valuationType() == ValuationType.IOPV) {
                    premiumRate = roundRate((price - valuationValue) / valuationValue);
                } else {
                    navRefPremiumRate = roundRate((price - valuationValue) / valuationValue);
                }
            }

            points.add(new TimelinePoint(
                ts,
                price,
                turnover,
                changeRate,
                valuationValue,
                overview.valuationType() != null ? overview.valuationType() : ValuationType.NONE,
                premiumRate,
                navRefPremiumRate,
                overview.spreadRate()
            ));
        }
        return points;
    }

    private JsonNode readJson(URI uri) {
        try {
            String body = restTemplate.getForObject(uri, String.class);
            if (body == null || body.isBlank()) {
                return null;
            }
            return objectMapper.readTree(body);
        } catch (IOException | RestClientException ex) {
            return null;
        }
    }

    private String secId(InstrumentInfo instrument) {
        return ("SH".equalsIgnoreCase(instrument.getMarket()) ? "1." : "0.") + instrument.getCode();
    }

    private double parseDouble(String raw) {
        return Double.parseDouble(raw);
    }

    private double roundRate(double value) {
        return Math.round(value * 1_000_000.0) / 1_000_000.0;
    }
}
