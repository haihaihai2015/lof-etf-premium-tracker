package com.example.etfmonitor.service;

import com.example.etfmonitor.entity.DailyBar;
import com.example.etfmonitor.entity.InstrumentInfo;
import com.example.etfmonitor.repository.DailyBarRepository;
import com.example.etfmonitor.repository.InstrumentInfoRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class HistoricalDataSyncService {

    private static final Logger log = LoggerFactory.getLogger(HistoricalDataSyncService.class);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final String SINA_KLINE_URL = "https://money.finance.sina.com.cn/quotes_service/api/json_v2.php/CN_MarketData.getKLineData";
    private static final String NAV_HISTORY_URL = "https://api.fund.eastmoney.com/f10/lsjz";
    private static final int KLINE_LIMIT = 1300; // ~5 years of daily bars
    private static final int NAV_PAGE_SIZE = 20;
    private static final long API_DELAY_MS = 500;
    private static final int MAX_RETRIES = 4;
    private static final int SYNC_THREADS = 2;

    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;
    private final DailyBarRepository dailyBarRepository;
    private final InstrumentInfoRepository instrumentInfoRepository;

    private final AtomicBoolean syncing = new AtomicBoolean(false);
    private final AtomicInteger syncProgress = new AtomicInteger(0);
    private final AtomicInteger syncTotal = new AtomicInteger(0);
    private volatile String syncStatus = "idle";
    private volatile String syncMessage = "";

    public HistoricalDataSyncService(ObjectMapper objectMapper,
                                      RestTemplateBuilder restTemplateBuilder,
                                      DailyBarRepository dailyBarRepository,
                                      InstrumentInfoRepository instrumentInfoRepository,
                                      @Value("${app.market.eastmoney.timeout-ms:8000}") long timeoutMs) {
        this.objectMapper = objectMapper;
        this.dailyBarRepository = dailyBarRepository;
        this.instrumentInfoRepository = instrumentInfoRepository;
        // Use SimpleClientHttpRequestFactory with keep-alive disabled
        // to avoid "Unexpected end of file" from EastMoney servers
        org.springframework.http.client.SimpleClientHttpRequestFactory factory =
            new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) timeoutMs);
        factory.setReadTimeout((int) (timeoutMs * 2));
        factory.setOutputStreaming(false);
        this.restTemplate = new RestTemplate(factory);
    }

    public Map<String, Object> getSyncStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("status", syncStatus);
        status.put("progress", syncProgress.get());
        status.put("total", syncTotal.get());
        status.put("message", syncMessage);
        return status;
    }

    public Map<String, Object> getHistoryRange() {
        Map<String, Object> range = new HashMap<>();
        Optional<LocalDate> min = dailyBarRepository.findMinTradeDate();
        Optional<LocalDate> max = dailyBarRepository.findMaxTradeDate();
        range.put("minDate", min.map(DATE_FMT::format).orElse(null));
        range.put("maxDate", max.map(DATE_FMT::format).orElse(null));
        range.put("totalBars", dailyBarRepository.count());

        Optional<LocalDate> premMin = dailyBarRepository.findMinPremiumDate();
        Optional<LocalDate> premMax = dailyBarRepository.findMaxPremiumDate();
        range.put("premiumMinDate", premMin.map(DATE_FMT::format).orElse(null));
        range.put("premiumMaxDate", premMax.map(DATE_FMT::format).orElse(null));
        range.put("premiumInstruments", dailyBarRepository.countDistinctCodesWithPremium());
        return range;
    }

    public void startSync() {
        if (!syncing.compareAndSet(false, true)) {
            log.warn("Historical sync already in progress");
            return;
        }
        Thread syncThread = new Thread(() -> {
            try {
                doSync();
            } catch (Exception e) {
                log.error("Historical sync failed", e);
                syncStatus = "error";
                syncMessage = e.getMessage();
            } finally {
                syncing.set(false);
            }
        }, "history-sync");
        syncThread.setDaemon(true);
        syncThread.start();
    }

    public void startSyncOne(String code) {
        if (!syncing.compareAndSet(false, true)) {
            log.warn("Historical sync already in progress");
            return;
        }
        Thread syncThread = new Thread(() -> {
            try {
                doSyncOne(code);
            } catch (Exception e) {
                log.error("Historical sync failed for {}", code, e);
                syncStatus = "error";
                syncMessage = e.getMessage();
            } finally {
                syncing.set(false);
            }
        }, "history-sync-one-" + code);
        syncThread.setDaemon(true);
        syncThread.start();
    }

    private void doSyncOne(String code) {
        syncStatus = "running";
        syncMessage = "正在同步 " + code + " ...";
        syncTotal.set(1);
        syncProgress.set(0);

        InstrumentInfo inst = instrumentInfoRepository.findById(code)
            .orElseThrow(() -> new IllegalArgumentException("未找到标的: " + code));

        boolean incremental = dailyBarRepository.countByCode(code) > 0;
        int klineCount = syncKline(inst, incremental);
        sleep(API_DELAY_MS);
        int navCount = syncNav(inst, incremental);
        sleep(API_DELAY_MS);
        computePremiumRates(code);

        syncProgress.set(1);
        syncStatus = "done";
        syncMessage = String.format("同步完成: %s %s, K线 %d, NAV %d", code, inst.getName(), klineCount, navCount);
        log.info("Historical data sync completed for {}: kline={}, nav={}", code, klineCount, navCount);
    }

    private void doSync() {
        syncStatus = "running";
        syncMessage = "正在获取标的列表...";

        List<InstrumentInfo> instruments = instrumentInfoRepository.findAll().stream()
            .filter(i -> i.getInstrumentType() == com.example.etfmonitor.domain.InstrumentType.LOF)
            .collect(java.util.stream.Collectors.toList());

        // Prioritize instruments without data (new first, then incremental)
        Map<String, Long> barCounts = new HashMap<>();
        for (InstrumentInfo inst : instruments) {
            barCounts.put(inst.getCode(), dailyBarRepository.countByCode(inst.getCode()));
        }
        instruments.sort(java.util.Comparator.comparingLong(i -> barCounts.getOrDefault(i.getCode(), 0L)));

        syncTotal.set(instruments.size());
        syncProgress.set(0);

        log.info("Starting historical data sync for {} instruments with {} worker threads",
            instruments.size(), SYNC_THREADS);

        // Split instruments into chunks for parallel processing
        java.util.concurrent.ExecutorService executor =
            java.util.concurrent.Executors.newFixedThreadPool(SYNC_THREADS);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
        // Use a shared queue for work distribution
        java.util.concurrent.BlockingQueue<InstrumentInfo> queue =
            new java.util.concurrent.LinkedBlockingQueue<>(instruments);

        for (int t = 0; t < SYNC_THREADS; t++) {
            final int threadIdx = t;
            futures.add(executor.submit(() -> {
                int threadConsecFail = 0;
                InstrumentInfo inst;
                while ((inst = queue.poll()) != null) {
                    int progress = syncProgress.incrementAndGet();
                    syncMessage = String.format("(%d/%d) [T%d] 正在同步 %s %s ...",
                        progress, syncTotal.get(), threadIdx, inst.getCode(), inst.getName());
                    try {
                        boolean incremental = barCounts.getOrDefault(inst.getCode(), 0L) > 0;
                        sleep(API_DELAY_MS);
                        int klineCount = syncKline(inst, incremental);
                        sleep(API_DELAY_MS);
                        int navCount = syncNav(inst, incremental);
                        sleep(500);
                        computePremiumRates(inst.getCode());

                        if (klineCount > 0 || navCount > 0) {
                            successCount.incrementAndGet();
                        }
                        threadConsecFail = 0;
                    } catch (Exception e) {
                        failCount.incrementAndGet();
                        threadConsecFail++;
                        log.warn("[T{}] Failed to sync {}: {}", threadIdx, inst.getCode(), e.getMessage());
                        if (threadConsecFail >= 3) {
                            long cooldown = Math.min(threadConsecFail * 5000L, 60000L);
                            log.info("[T{}] Cooling down {}s after {} consecutive failures...",
                                threadIdx, cooldown / 1000, threadConsecFail);
                            sleep(cooldown);
                        }
                        if (threadConsecFail >= 30) {
                            log.warn("[T{}] Too many consecutive failures ({}), thread stopping.", threadIdx, threadConsecFail);
                            break;
                        }
                    }
                }
            }));
        }

        // Wait for all threads to complete
        for (java.util.concurrent.Future<?> f : futures) {
            try { f.get(); } catch (Exception e) { log.warn("Sync thread error", e); }
        }
        executor.shutdown();

        syncStatus = "done";
        syncMessage = String.format("同步完成: 成功 %d, 失败 %d, 共 %d",
            successCount.get(), failCount.get(), instruments.size());
        log.info("Historical data sync completed: success={}, fail={}, total={}",
            successCount.get(), failCount.get(), instruments.size());
    }

    private int syncKline(InstrumentInfo inst, boolean incremental) {
        // Sina symbol: sh510300 or sz161226
        String symbol = ("SH".equalsIgnoreCase(inst.getMarket()) ? "sh" : "sz") + inst.getCode();

        URI uri = UriComponentsBuilder.fromUriString(SINA_KLINE_URL)
            .queryParam("symbol", symbol)
            .queryParam("scale", "240")     // daily (240-min bars = 1 day)
            .queryParam("ma", "no")
            .queryParam("datalen", "2600")  // ~10 years
            .build(true)
            .toUri();

        JsonNode root = readJsonWithRetry(uri, "https://finance.sina.com.cn/");
        if (root == null || !root.isArray() || root.isEmpty()) return 0;

        Optional<LocalDate> lastDate = incremental
            ? dailyBarRepository.findMaxTradeDateByCode(inst.getCode())
            : Optional.empty();

        List<DailyBar> batch = new ArrayList<>();
        int count = 0;

        for (JsonNode item : root) {
            String dayStr = item.path("day").asText();
            if (dayStr == null || dayStr.isBlank()) continue;

            LocalDate date = LocalDate.parse(dayStr, DATE_FMT);
            if (lastDate.isPresent() && !date.isAfter(lastDate.get())) continue;

            Double open = parseDouble(item.path("open").asText());
            Double close = parseDouble(item.path("close").asText());
            Double high = parseDouble(item.path("high").asText());
            Double low = parseDouble(item.path("low").asText());
            Long volumeShares = parseLong(item.path("volume").asText());  // shares (股)

            // Approximate turnover: volume × VWAP ≈ volume × (high+low)/2
            Long turnover = null;
            if (volumeShares != null && high != null && low != null) {
                turnover = Math.round(volumeShares * (high + low) / 2.0);
            }

            batch.add(new DailyBar(inst.getCode(), date, open, close, high, low, volumeShares, turnover, null, null));
            count++;

            if (batch.size() >= 200) {
                dailyBarRepository.saveAll(batch);
                batch.clear();
            }
        }

        if (!batch.isEmpty()) {
            dailyBarRepository.saveAll(batch);
        }

        return count;
    }

    private int syncNav(InstrumentInfo inst, boolean incremental) {
        // Batch load all existing bars into memory for fast lookup
        Map<LocalDate, DailyBar> barMap = new HashMap<>();
        dailyBarRepository.findByCodeOrderByTradeDateAsc(inst.getCode())
            .forEach(b -> barMap.put(b.getTradeDate(), b));
        if (barMap.isEmpty()) return 0;

        LocalDate startDate = LocalDate.now().minusYears(5);
        LocalDate endDate = LocalDate.now();
        String startStr = startDate.format(DATE_FMT);
        String endStr = endDate.format(DATE_FMT);

        int totalNavCount = 0;
        int pageIndex = 1;
        boolean hasMore = true;
        int consecCleanPages = 0;
        List<DailyBar> dirtyBars = new ArrayList<>();

        while (hasMore) {
            URI uri = UriComponentsBuilder.fromUriString(NAV_HISTORY_URL)
                .queryParam("fundCode", inst.getCode())
                .queryParam("pageIndex", pageIndex)
                .queryParam("pageSize", NAV_PAGE_SIZE)
                .queryParam("startDate", startStr)
                .queryParam("endDate", endStr)
                .queryParam("_", System.currentTimeMillis())
                .build(true)
                .toUri();

            JsonNode root = readJsonWithRetry(uri, "https://fund.eastmoney.com/");
            if (root == null) break;

            JsonNode data = root.path("Data");
            if (data.isMissingNode() || data.isNull()) break;

            JsonNode lsjz = data.path("LSJZList");
            if (!lsjz.isArray() || lsjz.isEmpty()) break;

            int pageNavCount = 0;
            int prevDirty = dirtyBars.size();
            for (JsonNode item : lsjz) {
                String dateStr = item.path("FSRQ").asText("");
                if (dateStr.isBlank()) continue;

                LocalDate date = LocalDate.parse(dateStr, DATE_FMT);
                Double dwjz = parseDouble(item.path("DWJZ").asText(""));
                if (dwjz == null) continue;
                Boolean canSubscribe = parseTradeStatus(item.path("SGZT").asText(""));
                Boolean canRedeem = parseTradeStatus(item.path("SHZT").asText(""));

                DailyBar bar = barMap.get(date);
                if (bar != null) {
                    boolean dirty = false;
                    if (bar.getNav() == null || !bar.getNav().equals(dwjz)) {
                        bar.setNav(dwjz);
                        dirty = true;
                    }
                    if (canSubscribe != null && !canSubscribe.equals(bar.getCanSubscribe())) {
                        bar.setCanSubscribe(canSubscribe);
                        dirty = true;
                    }
                    if (canRedeem != null && !canRedeem.equals(bar.getCanRedeem())) {
                        bar.setCanRedeem(canRedeem);
                        dirty = true;
                    }
                    if (dirty) {
                        dirtyBars.add(bar);
                    }
                }

                pageNavCount++;
                totalNavCount++;
            }

            int totalCount = root.path("TotalCount").asInt(0);
            int pageDirty = dirtyBars.size() - prevDirty;
            if (pageDirty == 0) consecCleanPages++; else consecCleanPages = 0;

            hasMore = totalNavCount < totalCount && pageNavCount > 0;
            // Early stop: if incremental and 2 consecutive pages had no new data
            if (incremental && consecCleanPages >= 2) {
                log.info("syncNav {} early stop at page {} (no new data)", inst.getCode(), pageIndex);
                hasMore = false;
            }
            if (pageIndex % 10 == 0 || !hasMore) {
                log.info("syncNav {} page={} totalFetched={}/{} dirtyCount={}",
                    inst.getCode(), pageIndex, totalNavCount, totalCount, dirtyBars.size());
            }
            pageIndex++;
        }

        // Batch save in chunks of 200 to avoid large transactions
        for (int i = 0; i < dirtyBars.size(); i += 200) {
            dailyBarRepository.saveAll(dirtyBars.subList(i, Math.min(i + 200, dirtyBars.size())));
        }

        return totalNavCount;
    }

    private void computePremiumRates(String code) {
        List<DailyBar> bars = dailyBarRepository.findByCodeAndPremiumRateIsNullAndNavIsNotNull(code);
        List<DailyBar> updated = new ArrayList<>();
        for (DailyBar bar : bars) {
            if (bar.getClosePrice() != null && bar.getNav() > 0) {
                double rate = (bar.getClosePrice() - bar.getNav()) / bar.getNav();
                rate = Math.round(rate * 1_000_000.0) / 1_000_000.0;
                bar.setPremiumRate(rate);
                updated.add(bar);
            }
        }
        if (!updated.isEmpty()) {
            dailyBarRepository.saveAll(updated);
        }
    }

    private JsonNode readJsonWithRetry(URI uri, String referer) {
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            java.net.HttpURLConnection conn = null;
            try {
                java.net.URLConnection rawConn = uri.toURL().openConnection();
                conn = (java.net.HttpURLConnection) rawConn;
                conn.setRequestMethod("GET");
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36");
                conn.setRequestProperty("Referer", referer);
                conn.setRequestProperty("Accept", "*/*");
                conn.setRequestProperty("Connection", "close");
                conn.setUseCaches(false);
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(16000);
                conn.connect();

                int code = conn.getResponseCode();
                if (code != 200) {
                    throw new java.io.IOException("HTTP " + code);
                }
                try (java.io.InputStream is = conn.getInputStream();
                     java.io.BufferedReader reader = new java.io.BufferedReader(
                         new java.io.InputStreamReader(is, java.nio.charset.StandardCharsets.UTF_8))) {
                    StringBuilder sb = new StringBuilder();
                    char[] buf = new char[8192];
                    int n;
                    while ((n = reader.read(buf)) != -1) sb.append(buf, 0, n);
                    String body = sb.toString();
                    if (body.isBlank()) return null;
                    return objectMapper.readTree(body);
                }
            } catch (Exception e) {
                if (attempt < MAX_RETRIES) {
                    long backoff = 3000L * (long) Math.pow(2, attempt - 1);
                    log.info("Retry {}/{} after {}s for {}: {}", attempt, MAX_RETRIES, backoff / 1000, uri, e.getMessage());
                    sleep(backoff);
                } else {
                    log.warn("HTTP failed after {} retries: {} -> {}", MAX_RETRIES, uri, e.getMessage());
                }
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
        return null;
    }

    private Double parseDouble(String raw) {
        if (raw == null || raw.isBlank() || "--".equals(raw) || "".equals(raw)) return null;
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Long parseTurnover(String raw) {
        if (raw == null || raw.isBlank() || "--".equals(raw)) return null;
        try {
            return Math.round(Double.parseDouble(raw));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Long parseLong(String raw) {
        if (raw == null || raw.isBlank() || "--".equals(raw)) return null;
        try {
            return Math.round(Double.parseDouble(raw));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Boolean parseTradeStatus(String raw) {
        if (raw == null || raw.isBlank()) return null;
        if (raw.contains("暂停") || raw.contains("封闭")) return false;
        if (raw.contains("开放") || raw.contains("场内") || raw.contains("限制大额")) return true;
        return null;
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
