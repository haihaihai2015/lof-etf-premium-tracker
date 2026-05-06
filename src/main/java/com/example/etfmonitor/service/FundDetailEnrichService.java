package com.example.etfmonitor.service;

import com.example.etfmonitor.entity.InstrumentInfo;
import com.example.etfmonitor.repository.InstrumentInfoRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Enriches instrument details from EastMoney fund API (company, status, type, etc.)
 */
@Service
public class FundDetailEnrichService {

    private static final Logger log = LoggerFactory.getLogger(FundDetailEnrichService.class);

    private final InstrumentInfoRepository instrumentInfoRepository;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;
    private final String providerMode;
    private final String fundBaseUrl;
    private final int maxDetailFetchPerCycle;

    public FundDetailEnrichService(InstrumentInfoRepository instrumentInfoRepository,
                                    ObjectMapper objectMapper,
                                    RestTemplateBuilder restTemplateBuilder,
                                    @Value("${app.market.provider-mode:mock}") String providerMode,
                                    @Value("${app.market.eastmoney.fund-base-url}") String fundBaseUrl,
                                    @Value("${app.market.eastmoney.timeout-ms}") long timeoutMs,
                                    @Value("${app.market.eastmoney.max-detail-fetch-per-cycle:200}") int maxDetailFetchPerCycle) {
        this.instrumentInfoRepository = instrumentInfoRepository;
        this.objectMapper = objectMapper;
        this.providerMode = providerMode;
        this.fundBaseUrl = fundBaseUrl;
        this.maxDetailFetchPerCycle = maxDetailFetchPerCycle;
        this.restTemplate = restTemplateBuilder
            .setConnectTimeout(Duration.ofMillis(timeoutMs))
            .setReadTimeout(Duration.ofMillis(timeoutMs))
            .build();
    }

    public void enrichInstrumentDetails() {
        if (!"real".equalsIgnoreCase(providerMode)) {
            return;
        }
        List<InstrumentInfo> unenriched = instrumentInfoRepository.findAll().stream()
            .filter(i -> i.getFundCompany() == null)
            .limit(maxDetailFetchPerCycle)
            .collect(Collectors.toList());
        if (unenriched.isEmpty()) {
            log.info("All instruments already enriched, skipping detail fetch.");
            return;
        }

        log.info("Enriching {} instruments with fund details from EastMoney...", unenriched.size());
        int enriched = 0;
        boolean loggedSample = false;
        for (InstrumentInfo instrument : unenriched) {
            try {
                if (enrichFromApi(instrument, !loggedSample)) {
                    enriched++;
                    loggedSample = true;
                }
            } catch (Exception ex) {
                log.warn("Failed to enrich {}: {}", instrument.getCode(), ex.getMessage());
            }
        }
        instrumentInfoRepository.saveAll(unenriched);
        log.info("Enriched {}/{} instruments with fund details.", enriched, unenriched.size());
    }

    private boolean enrichFromApi(InstrumentInfo instrument, boolean logFields) {
        JsonNode root = readJsonEastMoney(UriComponentsBuilder.fromUriString(fundBaseUrl)
            .queryParam("FCODE", instrument.getCode())
            .queryParam("deviceid", "Wap")
            .queryParam("plat", "Wap")
            .queryParam("product", "EFund")
            .queryParam("version", "2.0.0")
            .build(true)
            .toUri());
        if (root == null || root.path("ErrCode").asInt(-1) != 0) {
            return false;
        }

        JsonNode data = root.path("Datas");
        if (data.isMissingNode() || data.isNull()) {
            return false;
        }

        if (logFields) {
            logAvailableFields(instrument.getCode(), data);
        }

        String company = textOrNull(data, "JJGSMC");
        if (company == null) {
            company = textOrNull(data, "JJGS");
        }
        if (company != null) {
            instrument.setFundCompany(company);
        } else {
            instrument.setFundCompany("--");
        }

        String sgzt = textOrNull(data, "SGZT");
        if (sgzt != null) {
            instrument.setCanSubscribe(!sgzt.contains("暂停") && !sgzt.contains("封闭"));
            if (sgzt.contains("暂停") || sgzt.contains("封闭")) {
                instrument.setDailyPurchaseLimit(0L);
            } else if (sgzt.contains("开放") && !sgzt.contains("限")) {
                instrument.setDailyPurchaseLimit(-1L);
            }
        }

        String shzt = textOrNull(data, "SHZT");
        if (shzt != null) {
            instrument.setCanRedeem(!shzt.contains("暂停") && !shzt.contains("封闭"));
        }

        Long minUnit = parseLongFromFields(data, "MINBUYAPPLY", "MINSGAPPLY", "MINSG");
        if (minUnit != null && minUnit >= 1) {
            instrument.setMinimumSubscriptionUnit(minUnit);
        }

        String indexName = textOrNull(data, "INDEXNAME");
        if (indexName == null) {
            indexName = textOrNull(data, "BENCH");
        }
        if (indexName != null && !indexName.isBlank() && !"--".equals(indexName)) {
            instrument.setIndexName(indexName);
        }

        String ftype = textOrNull(data, "FTYPE");
        if (ftype != null && !ftype.isBlank()) {
            instrument.setFundType(ftype);
        }

        return true;
    }

    private void logAvailableFields(String code, JsonNode data) {
        StringBuilder sb = new StringBuilder();
        sb.append("EastMoney FundBaseTypeInformation fields for ").append(code).append(": ");
        Iterator<String> names = data.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            String value = data.path(name).asText("");
            if (value.length() > 50) {
                value = value.substring(0, 50) + "...";
            }
            sb.append(name).append("=").append(value);
            if (names.hasNext()) {
                sb.append(", ");
            }
        }
        log.info(sb.toString());
    }

    String textOrNull(JsonNode node, String field) {
        JsonNode child = node.path(field);
        if (child.isMissingNode() || child.isNull()) {
            return null;
        }
        String text = child.asText("").trim();
        return text.isEmpty() ? null : text;
    }

    Long parseLongFromFields(JsonNode data, String... fieldNames) {
        for (String field : fieldNames) {
            String raw = textOrNull(data, field);
            if (raw == null) {
                continue;
            }
            try {
                double value = Double.parseDouble(raw);
                if (value >= 1) {
                    return Math.round(value);
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    private JsonNode readJsonEastMoney(URI uri) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.USER_AGENT, "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36");
            headers.set(HttpHeaders.ACCEPT, "application/json, text/plain, */*");
            headers.set(HttpHeaders.REFERER, "https://fund.eastmoney.com/");
            ResponseEntity<String> response = restTemplate.exchange(uri, HttpMethod.GET, new HttpEntity<>(headers), String.class);
            if (response.getBody() == null || response.getBody().isBlank()) {
                return null;
            }
            return objectMapper.readTree(Objects.requireNonNull(response.getBody()));
        } catch (IOException | RestClientException ex) {
            log.warn("EastMoney fund detail request failed for {}", uri, ex);
            return null;
        }
    }
}
