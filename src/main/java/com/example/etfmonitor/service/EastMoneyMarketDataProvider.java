package com.example.etfmonitor.service;

import com.example.etfmonitor.domain.InstrumentType;
import com.example.etfmonitor.domain.MarketSource;
import com.example.etfmonitor.domain.ValuationType;
import com.example.etfmonitor.entity.InstrumentInfo;
import com.example.etfmonitor.model.QuoteSnapshot;
import com.example.etfmonitor.model.ValuationSnapshot;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component("primaryMarketDataProvider")
@ConditionalOnProperty(name = "app.market.provider-mode", havingValue = "real", matchIfMissing = true)
public class EastMoneyMarketDataProvider implements MarketDataProvider {

    private static final Logger log = LoggerFactory.getLogger(EastMoneyMarketDataProvider.class);
    private static final DateTimeFormatter NAV_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter SINA_TS_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int SINA_BATCH_SIZE = 80;

    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;
    private final String sinaQuoteUrl;
    private final String navUrl;
    private final ZoneId zoneId;
    private final Duration navCacheTtl;
    private final int maxNavFetchPerCycle;
    private final Map<String, CachedValuation> cachedValuations = new ConcurrentHashMap<>();

    public EastMoneyMarketDataProvider(ObjectMapper objectMapper,
                                       RestTemplateBuilder restTemplateBuilder,
                                       @Value("${app.market.sina.quote-url}") String sinaQuoteUrl,
                                       @Value("${app.market.eastmoney.nav-url}") String navUrl,
                                       @Value("${app.market.zone-id}") String zoneId,
                                       @Value("${app.market.eastmoney.timeout-ms}") long timeoutMs,
                                       @Value("${app.market.eastmoney.nav-cache-minutes}") long navCacheMinutes,
                                       @Value("${app.market.eastmoney.max-nav-fetch-per-cycle:30}") int maxNavFetchPerCycle) {
        this.objectMapper = objectMapper;
        this.sinaQuoteUrl = sinaQuoteUrl;
        this.navUrl = navUrl;
        this.zoneId = ZoneId.of(zoneId);
        this.navCacheTtl = Duration.ofMinutes(navCacheMinutes);
        this.maxNavFetchPerCycle = maxNavFetchPerCycle;
        this.restTemplate = restTemplateBuilder
            .setConnectTimeout(Duration.ofMillis(timeoutMs))
            .setReadTimeout(Duration.ofMillis(timeoutMs))
            .build();
    }

    @Override
    public Map<String, QuoteSnapshot> fetchQuotes(Collection<InstrumentInfo> instruments) {
        Map<String, QuoteSnapshot> result = new HashMap<>();
        List<InstrumentInfo> batch = new ArrayList<>(SINA_BATCH_SIZE);
        for (InstrumentInfo instrument : instruments) {
            batch.add(instrument);
            if (batch.size() >= SINA_BATCH_SIZE) {
                result.putAll(fetchQuoteBatch(batch));
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            result.putAll(fetchQuoteBatch(batch));
        }
        return result;
    }

    @Override
    public Map<String, ValuationSnapshot> fetchValuations(Collection<InstrumentInfo> instruments,
                                                          Map<String, QuoteSnapshot> quotes) {
        Map<String, ValuationSnapshot> result = new HashMap<>();
        List<InstrumentInfo> missing = new ArrayList<>();
        for (InstrumentInfo instrument : instruments) {
            CachedValuation cached = cachedValuations.get(instrument.getCode());
            if (cached != null && cached.snapshot().valuationType() != ValuationType.NONE) {
                result.put(instrument.getCode(), cached.snapshot());
            }
            if (cached == null || cached.isExpired(navCacheTtl, zoneId)) {
                missing.add(instrument);
            }
        }

        int limit = Math.min(maxNavFetchPerCycle, missing.size());
        for (int i = 0; i < limit; i++) {
            InstrumentInfo instrument = missing.get(i);
            try {
                ValuationSnapshot snapshot = fetchLatestNavReference(instrument);
                if (snapshot != null) {
                    cachedValuations.put(instrument.getCode(), new CachedValuation(snapshot, LocalDateTime.now(zoneId)));
                    if (snapshot.valuationType() != ValuationType.NONE) {
                        result.put(instrument.getCode(), snapshot);
                    }
                }
            } catch (Exception ex) {
                log.warn("Failed to fetch valuation for {}", instrument.getCode(), ex);
            }
        }

        return result;
    }

    @Override
    public boolean isHealthy() {
        return true;
    }

    private Map<String, QuoteSnapshot> fetchQuoteBatch(List<InstrumentInfo> batch) {
        String symbols = String.join(",", batch.stream().map(this::sinaSymbol).toList());
        URI uri = UriComponentsBuilder.fromUriString(sinaQuoteUrl)
            .queryParam("list", symbols)
            .build(true)
            .toUri();
        String body = readText(uri, sinaHeaders());
        if (body == null || body.isBlank()) {
            log.warn("Sina quote payload missing for batch size={}", batch.size());
            return Map.of();
        }

        Map<String, QuoteSnapshot> result = new HashMap<>();
        String[] lines = body.split("\\r?\\n");
        for (String line : lines) {
            QuoteSnapshot snapshot = parseSinaQuoteLine(line);
            if (snapshot != null) {
                result.put(snapshot.code(), snapshot);
            }
        }
        return result;
    }

    private QuoteSnapshot parseSinaQuoteLine(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        int start = line.indexOf("hq_str_");
        int equals = line.indexOf('=');
        if (start < 0 || equals < 0 || equals <= start + 7) {
            return null;
        }

        String symbol = line.substring(start + 7, equals);
        String code = symbol.length() > 2 ? symbol.substring(2) : symbol;
        String payload = line.substring(equals + 1).trim();
        if (payload.startsWith("\"")) {
            payload = payload.substring(1);
        }
        if (payload.endsWith("\";")) {
            payload = payload.substring(0, payload.length() - 2);
        } else if (payload.endsWith("\"")) {
            payload = payload.substring(0, payload.length() - 1);
        }
        if (payload.isBlank()) {
            return null;
        }

        String[] parts = payload.split(",");
        if (parts.length < 32) {
            log.warn("Sina quote line malformed for {} line={}", code, line);
            return null;
        }

        Double price = parseNullableDouble(parts[3]);
        Double preClose = parseNullableDouble(parts[2]);
        if (price == null || price <= 0) {
            return null;
        }

        Double bid1 = parseNullableDouble(parts[11]);
        Double ask1 = parseNullableDouble(parts[21]);
        Double turnoverRaw = parseNullableDouble(parts[9]);
        LocalDateTime quoteTs = parseSinaTimestamp(parts[30], parts[31]);
        double changeRate = preClose != null && preClose > 0
            ? roundRate((price - preClose) / preClose)
            : 0.0;

        return new QuoteSnapshot(
            code,
            roundPrice(price),
            nullablePrice(bid1),
            nullablePrice(ask1),
            turnoverRaw == null ? 0L : Math.round(turnoverRaw),
            changeRate,
            quoteTs,
            LocalDateTime.now(zoneId),
            MarketSource.SINA,
            false
        );
    }

    private ValuationSnapshot fetchLatestNavReference(InstrumentInfo instrument) {
        JsonNode root = readJson(UriComponentsBuilder.fromUriString(navUrl)
            .queryParam("FCODE", instrument.getCode())
            .queryParam("RANGE", "y")
            .queryParam("deviceid", "Wap")
            .queryParam("plat", "Wap")
            .queryParam("product", "EFund")
            .queryParam("version", "2.0.0")
            .build(true)
            .toUri());
        if (root == null || root.path("ErrCode").asInt(-1) != 0) {
            log.warn("Eastmoney NAV payload missing for {}", instrument.getCode());
            return noneSnapshot(instrument.getCode());
        }

        JsonNode dataArray = root.path("Datas");
        if (!dataArray.isArray() || dataArray.isEmpty()) {
            log.warn("Eastmoney NAV data array empty for {}", instrument.getCode());
            return noneSnapshot(instrument.getCode());
        }

        JsonNode latest = dataArray.get(dataArray.size() - 1);
        Double nav = parseNullableDouble(latest.path("DWJZ").asText());
        LocalDate navDate = parseNavDate(latest.path("FSRQ").asText());
        if (nav == null || nav <= 0 || navDate == null) {
            return noneSnapshot(instrument.getCode());
        }

        return new ValuationSnapshot(
            instrument.getCode(),
            instrument.getInstrumentType() == InstrumentType.ETF ? ValuationType.NAV_REF : ValuationType.NAV_REF,
            roundPrice(nav),
            LocalDateTime.now(zoneId),
            navDate,
            null,
            null,
            null
        );
    }

    private ValuationSnapshot noneSnapshot(String code) {
        return new ValuationSnapshot(code, ValuationType.NONE, null, null, null, null, null, null);
    }

    private JsonNode readJson(URI uri) {
        String body = readText(uri, defaultJsonHeaders());
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(body);
        } catch (IOException ex) {
            log.warn("Failed to parse JSON response for {}", uri, ex);
            return null;
        }
    }

    private String readText(URI uri, HttpHeaders headers) {
        try {
            HttpEntity<Void> entity = new HttpEntity<>(headers);
            ResponseEntity<String> response = restTemplate.exchange(uri, HttpMethod.GET, entity, String.class);
            return response.getBody();
        } catch (RestClientException ex) {
            log.warn("HTTP request threw exception for {}", uri, ex);
            return null;
        }
    }

    private HttpHeaders defaultJsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.USER_AGENT, "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36");
        headers.set(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE + ", text/plain, */*");
        headers.set(HttpHeaders.REFERER, "https://quote.eastmoney.com/");
        return headers;
    }

    private HttpHeaders sinaHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.USER_AGENT, "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36");
        headers.set(HttpHeaders.ACCEPT, "text/plain, */*");
        headers.set(HttpHeaders.REFERER, "https://finance.sina.com.cn/");
        return headers;
    }

    private String sinaSymbol(InstrumentInfo instrument) {
        return ("SH".equalsIgnoreCase(instrument.getMarket()) ? "sh" : "sz") + instrument.getCode();
    }

    private Double parseNullableDouble(String raw) {
        if (raw == null || raw.isBlank() || "--".equals(raw)) {
            return null;
        }
        return Double.parseDouble(raw);
    }

    private Double nullablePrice(Double value) {
        return value == null || value <= 0 ? null : roundPrice(value);
    }

    private LocalDate parseNavDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return LocalDate.parse(raw, NAV_DATE_FORMATTER);
    }

    private LocalDateTime parseSinaTimestamp(String date, String time) {
        if (date == null || date.isBlank() || time == null || time.isBlank()) {
            return LocalDateTime.now(zoneId);
        }
        try {
            return LocalDateTime.parse(date + " " + time, SINA_TS_FORMATTER);
        } catch (DateTimeParseException ex) {
            return LocalDateTime.now(zoneId);
        }
    }

    private double roundPrice(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }

    private double roundRate(double value) {
        return Math.round(value * 1_000_000.0) / 1_000_000.0;
    }

    private record CachedValuation(ValuationSnapshot snapshot, LocalDateTime fetchedAt) {
        private boolean isExpired(Duration ttl, ZoneId zoneId) {
            return fetchedAt.plus(ttl).isBefore(LocalDateTime.now(zoneId))
                || snapshot.navDate() == null
                || snapshot.navDate().isBefore(LocalDate.now(zoneId).minusDays(7));
        }
    }
}
