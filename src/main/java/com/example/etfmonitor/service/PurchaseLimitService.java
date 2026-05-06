package com.example.etfmonitor.service;

import com.example.etfmonitor.domain.InstrumentType;
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
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Handles daily purchase limit enrichment and exchange-based minimum subscription units.
 */
@Service
public class PurchaseLimitService {

    private static final Logger log = LoggerFactory.getLogger(PurchaseLimitService.class);

    private final InstrumentInfoRepository instrumentInfoRepository;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;
    private final String providerMode;
    private final int maxDetailFetchPerCycle;

    public PurchaseLimitService(InstrumentInfoRepository instrumentInfoRepository,
                                 ObjectMapper objectMapper,
                                 RestTemplateBuilder restTemplateBuilder,
                                 @Value("${app.market.provider-mode:mock}") String providerMode,
                                 @Value("${app.market.eastmoney.timeout-ms}") long timeoutMs,
                                 @Value("${app.market.eastmoney.max-detail-fetch-per-cycle:200}") int maxDetailFetchPerCycle) {
        this.instrumentInfoRepository = instrumentInfoRepository;
        this.objectMapper = objectMapper;
        this.providerMode = providerMode;
        this.maxDetailFetchPerCycle = maxDetailFetchPerCycle;
        this.restTemplate = restTemplateBuilder
            .setConnectTimeout(Duration.ofMillis(timeoutMs))
            .setReadTimeout(Duration.ofMillis(timeoutMs))
            .build();
    }

    public void enrichMinimumSubscriptionFromExchanges() {
        if (!"real".equalsIgnoreCase(providerMode)) {
            return;
        }

        Map<String, InstrumentInfo> byCode = new HashMap<>();
        instrumentInfoRepository.findAll().stream()
            .filter(i -> i.getMinimumSubscriptionUnit() == null)
            .filter(i -> i.getInstrumentType() != InstrumentType.MONEY_ETF)
            .forEach(i -> byCode.put(i.getCode(), i));
        if (byCode.isEmpty()) {
            return;
        }

        int updated = 0;
        updated += enrichFromSse(byCode);
        updated += enrichFromSzse(byCode);

        if (updated > 0) {
            instrumentInfoRepository.saveAll(byCode.values().stream()
                .filter(i -> i.getMinimumSubscriptionUnit() != null)
                .collect(Collectors.toList()));
            log.info("Enriched minimum subscription unit for {} instruments from exchange APIs.", updated);
        }
    }

    public void enrichDailyPurchaseLimits() {
        if (!"real".equalsIgnoreCase(providerMode)) {
            return;
        }
        List<InstrumentInfo> needLimit = instrumentInfoRepository.findAll().stream()
            .filter(i -> i.getDailyPurchaseLimit() == null)
            .filter(i -> i.getInstrumentType() != InstrumentType.MONEY_ETF)
            .limit(maxDetailFetchPerCycle)
            .collect(Collectors.toList());

        if (needLimit.isEmpty()) {
            log.info("All instruments already have daily purchase limit info.");
            return;
        }

        log.info("Fetching daily purchase limits from jbgk pages for {} instruments...", needLimit.size());
        int updated = 0;
        for (InstrumentInfo inst : needLimit) {
            try {
                Long limit = fetchDailyLimitFromJbgk(inst.getCode());
                if (limit != null) {
                    inst.setDailyPurchaseLimit(limit);
                    updated++;
                }
                Thread.sleep(150);
            } catch (Exception e) {
                log.debug("Failed to fetch jbgk for {}: {}", inst.getCode(), e.getMessage());
            }
        }

        if (updated > 0) {
            instrumentInfoRepository.saveAll(needLimit.stream()
                .filter(i -> i.getDailyPurchaseLimit() != null)
                .collect(Collectors.toList()));
        }
        log.info("Updated daily purchase limits for {}/{} instruments from jbgk pages.", updated, needLimit.size());
    }

    private Long fetchDailyLimitFromJbgk(String code) {
        try {
            URI uri = URI.create("https://fundf10.eastmoney.com/jbgk_" + code + ".html");
            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.USER_AGENT, "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36");
            headers.set(HttpHeaders.ACCEPT, "text/html,application/xhtml+xml");

            ResponseEntity<byte[]> response = restTemplate.exchange(uri, HttpMethod.GET, new HttpEntity<>(headers), byte[].class);
            byte[] bodyBytes = response.getBody();
            if (bodyBytes == null || bodyBytes.length == 0) return null;

            String contentType = response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE);
            String charset = "UTF-8";
            if (contentType != null && contentType.contains("charset=")) {
                charset = contentType.substring(contentType.indexOf("charset=") + 8).trim();
                if (charset.contains(";")) charset = charset.substring(0, charset.indexOf(";")).trim();
            }
            String html = new String(bodyBytes, java.nio.charset.Charset.forName(charset));

            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "日累计.*?上限[^\\d]*(\\d+[\\d,.]*)\\s*(万元|亿元|元)");
            java.util.regex.Matcher matcher = pattern.matcher(html);
            if (matcher.find()) {
                double amount = Double.parseDouble(matcher.group(1).replaceAll("[,，]", ""));
                String unit = matcher.group(2);
                if ("万元".equals(unit)) {
                    return Math.round(amount * 10000);
                } else if ("亿元".equals(unit)) {
                    return Math.round(amount * 100000000);
                } else {
                    return Math.round(amount);
                }
            }

            if (html.contains("开放申购") && !html.contains("限大额") && !html.contains("上限")) {
                return -1L;
            }
            if (html.contains("暂停申购") || html.contains("暂停大额")) {
                return 0L;
            }

            return null;
        } catch (Exception e) {
            log.debug("jbgk fetch failed for {}: {}", code, e.getMessage());
            return null;
        }
    }

    private int enrichFromSse(Map<String, InstrumentInfo> byCode) {
        try {
            URI uri = UriComponentsBuilder
                .fromUriString("https://query.sse.com.cn/commonQuery.do")
                .queryParam("isPagination", "true")
                .queryParam("pageHelp.pageSize", "1000")
                .queryParam("pageHelp.pageNo", "1")
                .queryParam("sqlId", "COMMON_SSE_ZQPZ_ETFLB_L_NEW")
                .build(true)
                .toUri();

            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.USER_AGENT, "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
            headers.set(HttpHeaders.ACCEPT, "application/json, text/plain, */*");
            headers.set(HttpHeaders.REFERER, "https://www.sse.com.cn/");
            ResponseEntity<String> response = restTemplate.exchange(uri, HttpMethod.GET, new HttpEntity<>(headers), String.class);
            if (response.getBody() == null || response.getBody().isBlank()) {
                log.warn("SSE ETF list API returned empty response.");
                return 0;
            }
            JsonNode root = objectMapper.readTree(response.getBody());
            if (root == null) return 0;

            JsonNode pageHelp = root.path("pageHelp");
            JsonNode data = pageHelp.isMissingNode() ? root.path("result") : pageHelp.path("data");
            if (!data.isArray()) {
                log.warn("SSE ETF list API unexpected structure. Top-level fields: {}", fieldNamesList(root));
                return 0;
            }
            if (data.size() > 0) {
                log.info("SSE ETF list first item fields: {}", fieldNamesWithValues(data.get(0)));
            }

            int count = 0;
            for (JsonNode item : data) {
                String code = firstNonBlank(item, "FUND_ID", "SEC_CODE", "SECURITY_CODE", "fundid1");
                if (code == null) continue;
                code = code.trim();
                InstrumentInfo inst = byCode.get(code);
                if (inst == null) continue;

                Long unit = parseLongFromFields(item, "CREATE_REDEEM_UNIT", "CREATION_UNIT",
                    "MIN_CREATION_UNIT", "ZXSGFE", "ZTLMGE", "creationredemptionunit");
                if (unit != null && unit >= 100) {
                    inst.setMinimumSubscriptionUnit(unit);
                    count++;
                }
            }
            log.info("SSE API: found {} creation units for tracked instruments.", count);
            return count;
        } catch (Exception ex) {
            log.warn("SSE ETF list API failed: {}", ex.getMessage());
            return 0;
        }
    }

    private int enrichFromSzse(Map<String, InstrumentInfo> byCode) {
        try {
            URI uri = UriComponentsBuilder
                .fromUriString("https://www.szse.cn/api/report/ShowReport/data")
                .queryParam("SHOWTYPE", "JSON")
                .queryParam("CATALOGID", "1945")
                .queryParam("TABKEY", "tab1")
                .queryParam("PAGENO", "1")
                .queryParam("PAGESIZE", "1000")
                .queryParam("random", String.valueOf(Math.random()))
                .build(true)
                .toUri();

            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.USER_AGENT, "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
            headers.set(HttpHeaders.ACCEPT, "application/json, text/plain, */*");
            headers.set(HttpHeaders.REFERER, "https://www.szse.cn/");
            ResponseEntity<String> response = restTemplate.exchange(uri, HttpMethod.GET, new HttpEntity<>(headers), String.class);
            if (response.getBody() == null || response.getBody().isBlank()) {
                log.warn("SZSE ETF list API returned empty response.");
                return 0;
            }
            JsonNode root = objectMapper.readTree(response.getBody());
            if (root == null) return 0;

            JsonNode data;
            if (root.isArray() && root.size() > 0) {
                JsonNode firstTab = root.get(0);
                data = firstTab.path("data");
                if (!data.isArray()) data = firstTab;
            } else if (root.has("data")) {
                data = root.path("data");
            } else {
                log.warn("SZSE ETF list API unexpected structure: {}", root.toString().substring(0, Math.min(200, root.toString().length())));
                return 0;
            }

            if (data.isArray() && data.size() > 0) {
                log.info("SZSE ETF list first item fields: {}", fieldNamesWithValues(data.get(0)));
            }

            int count = 0;
            for (JsonNode item : data) {
                String code = firstNonBlank(item, "jjdm", "FUND_CODE", "bsdm", "zqdm");
                if (code == null) continue;
                code = code.replaceAll("<[^>]*>", "").trim();
                InstrumentInfo inst = byCode.get(code);
                if (inst == null) continue;

                Long unit = parseLongFromFields(item, "zxscfe", "zxsgfe", "MIN_CREATION_UNIT",
                    "CREATION_UNIT", "CREATE_REDEEM_UNIT");
                if (unit == null) {
                    unit = parseLongFromHtmlFields(item, "zxscfe", "zxsgfe");
                }
                if (unit != null && unit >= 100) {
                    inst.setMinimumSubscriptionUnit(unit);
                    count++;
                }
            }
            log.info("SZSE API: found {} creation units for tracked instruments.", count);
            return count;
        } catch (Exception ex) {
            log.warn("SZSE ETF list API failed: {}", ex.getMessage());
            return 0;
        }
    }

    // --- Helper methods ---

    private String firstNonBlank(JsonNode node, String... fieldNames) {
        for (String name : fieldNames) {
            String val = textOrNull(node, name);
            if (val != null) return val;
        }
        return null;
    }

    private String textOrNull(JsonNode node, String field) {
        JsonNode child = node.path(field);
        if (child.isMissingNode() || child.isNull()) return null;
        String text = child.asText("").trim();
        return text.isEmpty() ? null : text;
    }

    private Long parseLongFromFields(JsonNode data, String... fieldNames) {
        for (String field : fieldNames) {
            String raw = textOrNull(data, field);
            if (raw == null) continue;
            try {
                double value = Double.parseDouble(raw);
                if (value >= 1) return Math.round(value);
            } catch (NumberFormatException ignored) {}
        }
        return null;
    }

    private Long parseLongFromHtmlFields(JsonNode data, String... fieldNames) {
        for (String field : fieldNames) {
            String raw = textOrNull(data, field);
            if (raw == null) continue;
            raw = raw.replaceAll("<[^>]*>", "").replaceAll("[,，\\s份]", "").trim();
            try {
                double value = Double.parseDouble(raw);
                if (value >= 100) return Math.round(value);
            } catch (NumberFormatException ignored) {}
        }
        return null;
    }

    private String fieldNamesList(JsonNode node) {
        StringBuilder sb = new StringBuilder();
        Iterator<String> names = node.fieldNames();
        while (names.hasNext()) {
            sb.append(names.next());
            if (names.hasNext()) sb.append(", ");
        }
        return sb.toString();
    }

    private String fieldNamesWithValues(JsonNode node) {
        StringBuilder sb = new StringBuilder();
        Iterator<String> names = node.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            String value = node.path(name).asText("");
            if (value.length() > 30) value = value.substring(0, 30) + "...";
            sb.append(name).append("=").append(value);
            if (names.hasNext()) sb.append(", ");
        }
        return sb.toString();
    }
}
