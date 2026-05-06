package com.example.etfmonitor.service;

import com.example.etfmonitor.domain.InstrumentStatus;
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
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class InstrumentUniverseSyncService {

    private static final Logger log = LoggerFactory.getLogger(InstrumentUniverseSyncService.class);

    private final InstrumentInfoRepository instrumentInfoRepository;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;
    private final FundDetailEnrichService fundDetailEnrichService;
    private final PurchaseLimitService purchaseLimitService;
    private final String providerMode;
    private final String sinaNodeUrl;
    private final String etfNode;
    private final String lofNode;
    private final int pageSize;
    private final Map<String, Long> minimumSubscriptionOverrides = minimumSubscriptionOverrides();

    public InstrumentUniverseSyncService(InstrumentInfoRepository instrumentInfoRepository,
                                         ObjectMapper objectMapper,
                                         RestTemplateBuilder restTemplateBuilder,
                                         FundDetailEnrichService fundDetailEnrichService,
                                         PurchaseLimitService purchaseLimitService,
                                         @Value("${app.market.provider-mode:mock}") String providerMode,
                                         @Value("${app.market.sina.node-url}") String sinaNodeUrl,
                                         @Value("${app.market.sina.etf-node}") String etfNode,
                                         @Value("${app.market.sina.lof-node}") String lofNode,
                                         @Value("${app.market.sina.universe-page-size}") int pageSize,
                                         @Value("${app.market.eastmoney.timeout-ms}") long timeoutMs) {
        this.instrumentInfoRepository = instrumentInfoRepository;
        this.objectMapper = objectMapper;
        this.fundDetailEnrichService = fundDetailEnrichService;
        this.purchaseLimitService = purchaseLimitService;
        this.providerMode = providerMode;
        this.sinaNodeUrl = sinaNodeUrl;
        this.etfNode = etfNode;
        this.lofNode = lofNode;
        this.pageSize = pageSize;
        this.restTemplate = restTemplateBuilder
            .setConnectTimeout(Duration.ofMillis(timeoutMs))
            .setReadTimeout(Duration.ofMillis(timeoutMs))
            .build();
    }

    public int syncUniverse() {
        if (!"real".equalsIgnoreCase(providerMode)) {
            seedDefaultsIfEmpty();
            return (int) instrumentInfoRepository.count();
        }

        Map<String, InstrumentInfo> universe = new LinkedHashMap<>();
        fetchByNode(etfNode).forEach(item -> universe.put(item.getCode(), item));
        fetchByNode(lofNode).forEach(item -> universe.put(item.getCode(), item));

        if (universe.isEmpty()) {
            log.warn("Universe sync returned empty result; keeping existing instrument pool.");
            seedDefaultsIfEmpty();
            return (int) instrumentInfoRepository.count();
        }

        instrumentInfoRepository.saveAll(universe.values());
        log.info("Universe sync completed, {} instruments available.", universe.size());

        fundDetailEnrichService.enrichInstrumentDetails();
        purchaseLimitService.enrichMinimumSubscriptionFromExchanges();
        purchaseLimitService.enrichDailyPurchaseLimits();

        return universe.size();
    }

    public void seedDefaultsIfEmpty() {
        if (instrumentInfoRepository.count() > 0) {
            return;
        }
        instrumentInfoRepository.saveAll(List.of(
            new InstrumentInfo("510300", "沪深300ETF", InstrumentType.ETF, "SH", "华泰柏瑞基金", "沪深300", true, true, InstrumentStatus.TRADING),
            new InstrumentInfo("510050", "上证50ETF", InstrumentType.ETF, "SH", "华夏基金", "上证50", true, true, InstrumentStatus.TRADING),
            new InstrumentInfo("512100", "中证1000ETF", InstrumentType.ETF, "SH", "南方基金", "中证1000", true, true, InstrumentStatus.TRADING),
            new InstrumentInfo("159915", "创业板ETF", InstrumentType.ETF, "SZ", "易方达基金", "创业板指", true, true, InstrumentStatus.TRADING),
            new InstrumentInfo("159941", "纳指ETF", InstrumentType.ETF, "SZ", "广发基金", "纳斯达克100", false, true, InstrumentStatus.TRADING),
            new InstrumentInfo("511830", "华泰货币ETF", InstrumentType.MONEY_ETF, "SH", "华泰柏瑞基金", null, true, true, InstrumentStatus.TRADING),
            new InstrumentInfo("159985", "饲料豆粕ETF", InstrumentType.ETF, "SZ", "华夏基金", null, 1_200_000L, true, true, InstrumentStatus.TRADING),
            new InstrumentInfo("160119", "500ETF联接LOF", InstrumentType.LOF, "SZ", "南方基金", "中证500", true, true, InstrumentStatus.TRADING),
            new InstrumentInfo("160135", "高铁LOF", InstrumentType.LOF, "SZ", "南方基金", "高铁产业", true, true, InstrumentStatus.TRADING),
            new InstrumentInfo("160625", "证保LOF", InstrumentType.LOF, "SZ", "鹏华基金", "800非银", true, true, InstrumentStatus.TRADING)
        ));
    }

    private List<InstrumentInfo> fetchByNode(String node) {
        List<InstrumentInfo> results = new ArrayList<>();
        int page = 1;
        while (true) {
            JsonNode root = readJson(UriComponentsBuilder.fromUriString(sinaNodeUrl)
                .queryParam("page", page)
                .queryParam("num", pageSize)
                .queryParam("sort", "symbol")
                .queryParam("asc", 1)
                .queryParam("node", node)
                .build(true)
                .toUri());
            if (root == null || !root.isArray() || root.isEmpty()) {
                break;
            }

            for (JsonNode item : root) {
                String code = item.path("code").asText();
                String name = item.path("name").asText();
                String symbol = item.path("symbol").asText();
                if (code == null || code.isBlank() || name == null || name.isBlank() || symbol == null || symbol.isBlank()) {
                    continue;
                }
                results.add(new InstrumentInfo(
                    code,
                    name,
                    inferType(node, name),
                    symbol.startsWith("sh") ? "SH" : "SZ",
                    null,
                    null,
                    minimumSubscriptionOverrides.get(code),
                    true,
                    true,
                    InstrumentStatus.TRADING
                ));
            }

            if (root.size() < pageSize) {
                break;
            }
            page++;
        }
        return results;
    }

    private InstrumentType inferType(String node, String name) {
        if (Objects.equals(node, lofNode)) {
            return InstrumentType.LOF;
        }
        return isMoneyEtf(name) ? InstrumentType.MONEY_ETF : InstrumentType.ETF;
    }

    private boolean isMoneyEtf(String name) {
        String normalized = name.replace(" ", "");
        return normalized.contains("货币")
            || normalized.contains("货利")
            || normalized.contains("银钱")
            || normalized.contains("天天金")
            || normalized.contains("天天利")
            || normalized.contains("日日鑫")
            || normalized.contains("日日盈")
            || normalized.contains("日利")
            || normalized.contains("添利")
            || normalized.contains("活期")
            || normalized.contains("快线")
            || normalized.contains("理财金")
            || normalized.contains("现金")
            || normalized.contains("保证金")
            || normalized.contains("财富宝");
    }


    private JsonNode readJson(URI uri) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.USER_AGENT, "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36");
            headers.set(HttpHeaders.ACCEPT, "application/json, text/plain, */*");
            headers.set(HttpHeaders.REFERER, "https://finance.sina.com.cn/");
            ResponseEntity<String> response = restTemplate.exchange(uri, HttpMethod.GET, new HttpEntity<>(headers), String.class);
            if (response.getBody() == null || response.getBody().isBlank()) {
                return null;
            }
            return objectMapper.readTree(Objects.requireNonNull(response.getBody()));
        } catch (IOException | RestClientException ex) {
            log.warn("Universe sync request failed for {}", uri, ex);
            return null;
        }
    }

    private Map<String, Long> minimumSubscriptionOverrides() {
        Map<String, Long> overrides = new HashMap<>();
        overrides.put("159985", 1_200_000L);
        return overrides;
    }
}
