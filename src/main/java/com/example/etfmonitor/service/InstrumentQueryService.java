package com.example.etfmonitor.service;

import com.example.etfmonitor.domain.InstrumentStatus;
import com.example.etfmonitor.domain.InstrumentType;
import com.example.etfmonitor.domain.SignalLevel;
import com.example.etfmonitor.domain.ValuationType;
import com.example.etfmonitor.entity.InstrumentInfo;
import com.example.etfmonitor.entity.MinuteSnapshot;
import com.example.etfmonitor.entity.ThresholdConfig;
import com.example.etfmonitor.model.FilterMetaResponse;
import com.example.etfmonitor.model.InstrumentDetailResponse;
import com.example.etfmonitor.model.InstrumentListResponse;
import com.example.etfmonitor.model.InstrumentOverview;
import com.example.etfmonitor.model.QuoteSnapshot;
import com.example.etfmonitor.model.SignalSnapshot;
import com.example.etfmonitor.model.TimelinePoint;
import com.example.etfmonitor.model.ValuationSnapshot;
import com.example.etfmonitor.repository.InstrumentInfoRepository;
import com.example.etfmonitor.repository.MinuteSnapshotRepository;
import com.example.etfmonitor.repository.ThresholdConfigRepository;

import java.util.Set;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class InstrumentQueryService {

    private final InstrumentInfoRepository instrumentInfoRepository;
    private final MinuteSnapshotRepository minuteSnapshotRepository;
    private final ThresholdConfigRepository thresholdConfigRepository;
    private final RealtimeStateStore realtimeStateStore;
    private final TradingSessionService tradingSessionService;
    private final EastMoneyTimelineService eastMoneyTimelineService;

    private volatile List<InstrumentOverview> precomputedOverviews = List.of();
    private volatile long precomputedAt = 0;

    public InstrumentQueryService(InstrumentInfoRepository instrumentInfoRepository,
                                  MinuteSnapshotRepository minuteSnapshotRepository,
                                  ThresholdConfigRepository thresholdConfigRepository,
                                  RealtimeStateStore realtimeStateStore,
                                  TradingSessionService tradingSessionService,
                                  EastMoneyTimelineService eastMoneyTimelineService) {
        this.instrumentInfoRepository = instrumentInfoRepository;
        this.minuteSnapshotRepository = minuteSnapshotRepository;
        this.thresholdConfigRepository = thresholdConfigRepository;
        this.realtimeStateStore = realtimeStateStore;
        this.tradingSessionService = tradingSessionService;
        this.eastMoneyTimelineService = eastMoneyTimelineService;
    }

    public void precomputeOverviews() {
        List<InstrumentInfo> allInstruments = instrumentInfoRepository.findAll();
        List<InstrumentOverview> overviews = allInstruments.stream()
            .map(this::toOverview)
            .collect(Collectors.toList());
        this.precomputedOverviews = overviews;
        this.precomputedAt = System.currentTimeMillis();
    }

    public void invalidateListCache() {
        // no-op, kept for backward compatibility; precompute is triggered by refresh
    }

    public InstrumentListResponse listInstruments(InstrumentType type, String sortBy, String sortOrder,
                                                  String keyword, Double premiumMin, Long turnoverMin, Double spreadMax,
                                                  boolean subscribableOnly, boolean purchasableOnly, boolean highLiquidityOnly,
                                                  Set<String> codes, int page, int pageSize) {
        Comparator<InstrumentOverview> comparator = buildComparator(sortBy, sortOrder);
        String normalizedKeyword = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);

        // Use pre-computed overviews (built once per refresh cycle)
        List<InstrumentOverview> source = precomputedOverviews;

        List<InstrumentOverview> rows = source.stream()
            .filter(row -> type == null || row.instrumentType() == type)
            .filter(row -> normalizedKeyword.isBlank()
                || row.code().toLowerCase(Locale.ROOT).contains(normalizedKeyword)
                || row.name().toLowerCase(Locale.ROOT).contains(normalizedKeyword))
            .filter(row -> premiumMin == null || row.instrumentType() == InstrumentType.MONEY_ETF
                || (deviationValue(row) != null && deviationValue(row) >= premiumMin))
            .filter(row -> turnoverMin == null || row.turnover() >= turnoverMin)
            .filter(row -> spreadMax == null || row.instrumentType() == InstrumentType.MONEY_ETF
                || row.spreadRate() == null || row.spreadRate() <= spreadMax)
            .filter(row -> !subscribableOnly || (row.canSubscribe() && row.canRedeem()))
            .filter(row -> !purchasableOnly || row.canSubscribe())
            .filter(row -> !highLiquidityOnly || row.turnover() >= 5_000_000L)
            .filter(row -> codes == null || codes.isEmpty() || codes.contains(row.code()))
            .sorted(comparator)
            .collect(Collectors.toList());

        int safePage = Math.max(1, page);
        int safeSize = Math.max(20, Math.min(pageSize, 200));
        int fromIndex = Math.min((safePage - 1) * safeSize, rows.size());
        int toIndex = Math.min(fromIndex + safeSize, rows.size());
        return new InstrumentListResponse(rows.subList(fromIndex, toIndex), rows.size(), safePage, safeSize);
    }

    public java.util.Map<String, Object> computeStats(InstrumentType type) {
        List<InstrumentOverview> source = precomputedOverviews;
        int premiumCount = 0, discountCount = 0, total = 0;
        for (InstrumentOverview ov : source) {
            if (type != null && ov.instrumentType() != type) continue;
            total++;
            Double dev = deviationValue(ov);
            if (dev != null) {
                if (dev > 0) premiumCount++;
                if (dev < 0) discountCount++;
            }
        }
        return java.util.Map.of("premiumCount", premiumCount, "discountCount", discountCount, "total", total);
    }

    public long countInstruments(InstrumentType type, String keyword) {
        String normalizedKeyword = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        return precomputedOverviews.stream()
            .filter(row -> type == null || row.instrumentType() == type)
            .filter(row -> normalizedKeyword.isBlank()
                || row.code().toLowerCase(Locale.ROOT).contains(normalizedKeyword)
                || row.name().toLowerCase(Locale.ROOT).contains(normalizedKeyword))
            .count();
    }

    public InstrumentDetailResponse getDetail(String code) {
        InstrumentInfo instrument = instrumentInfoRepository.findById(code)
            .orElseThrow(() -> new IllegalArgumentException("Unknown instrument code: " + code));
        InstrumentOverview overview = toOverview(instrument);
        return new InstrumentDetailResponse(overview, timeline(instrument, overview));
    }

    public List<TimelinePoint> timeline(String code) {
        InstrumentInfo instrument = instrumentInfoRepository.findById(code)
            .orElseThrow(() -> new IllegalArgumentException("Unknown instrument code: " + code));
        return timeline(instrument, toOverview(instrument));
    }

    public Map<String, List<Double>> sparklines(Set<String> codes) {
        Map<String, List<Double>> result = new java.util.HashMap<>();
        for (String code : codes) {
            List<MinuteSnapshot> snapshots = minuteSnapshotRepository
                .findTop120ByCodeOrderBySnapshotMinuteDesc(code);
            List<Double> values = snapshots.stream()
                .sorted(Comparator.comparing(MinuteSnapshot::getSnapshotMinute))
                .map(s -> s.getPremiumRate() != null ? s.getPremiumRate() : s.getNavRefPremiumRate())
                .filter(java.util.Objects::nonNull)
                .limit(30)
                .collect(Collectors.toList());
            result.put(code, values);
        }
        return result;
    }

    public FilterMetaResponse filterMeta() {
        Map<String, Object> defaults = new java.util.LinkedHashMap<>();
        Optional<ThresholdConfig> etfThreshold = thresholdConfigRepository.findByInstrumentType(InstrumentType.ETF);
        defaults.put("keyword", "");
        defaults.put("premiumMin", null);
        defaults.put("turnoverMin", 0L);
        defaults.put("spreadMax", etfThreshold.map(ThresholdConfig::getSpreadThreshold).orElse(0.0015));
        defaults.put("pageSize", 20);
        return new FilterMetaResponse(
            defaults,
            List.of("09:30-11:30", "13:00-15:00"),
            List.of(SignalLevel.WATCH.name(), SignalLevel.OBSERVE.name(), SignalLevel.IGNORE.name(), SignalLevel.INSUFFICIENT_DATA.name()),
            List.of("signal", "premium", "turnover", "spread", "changeRate", "price", "code", "name")
        );
    }

    private List<TimelinePoint> timeline(InstrumentInfo instrument, InstrumentOverview overview) {
        List<TimelinePoint> externalTimeline = eastMoneyTimelineService.fetchTimeline(instrument, overview);
        if (!externalTimeline.isEmpty()) {
            return externalTimeline;
        }

        List<MinuteSnapshot> snapshots = minuteSnapshotRepository.findTop120ByCodeOrderBySnapshotMinuteDesc(instrument.getCode());
        return snapshots.stream()
            .sorted(Comparator.comparing(MinuteSnapshot::getSnapshotMinute))
            .map(snapshot -> new TimelinePoint(
                snapshot.getSnapshotMinute(),
                snapshot.getPrice(),
                snapshot.getTurnover(),
                snapshot.getChangeRate(),
                snapshot.getValuationValue(),
                snapshot.getValuationType(),
                snapshot.getPremiumRate(),
                snapshot.getNavRefPremiumRate(),
                snapshot.getSpreadRate()
            ))
            .collect(Collectors.toList());
    }

    private InstrumentOverview toOverview(InstrumentInfo instrument) {
        QuoteSnapshot quote = realtimeStateStore.getQuote(instrument.getCode()).orElseGet(() -> fallbackQuote(instrument.getCode()));
        ValuationSnapshot valuation = realtimeStateStore.getValuation(instrument.getCode()).orElse(null);
        SignalSnapshot signal = realtimeStateStore.getSignal(instrument.getCode()).orElse(new SignalSnapshot(
            instrument.getCode(),
            SignalLevel.INSUFFICIENT_DATA,
            "等待采集刷新",
            null
        ));
        boolean stale = !tradingSessionService.isContinuousTrading() || quote.stale();
        return new InstrumentOverview(
            instrument.getCode(),
            instrument.getName(),
            instrument.getInstrumentType(),
            instrument.getMarket(),
            instrument.getFundCompany(),
            instrument.getIndexName(),
            instrument.getMinimumSubscriptionUnit(),
            instrument.getDailyPurchaseLimit(),
            instrument.getFundType(),
            tradingSessionService.isContinuousTrading() ? instrument.getStatus() : InstrumentStatus.CLOSED,
            instrument.isCanSubscribe(),
            instrument.isCanRedeem(),
            quote.price(),
            quote.bid1(),
            quote.ask1(),
            quote.turnover(),
            quote.changeRate(),
            valuation != null ? valuation.valuationType() : ValuationType.NONE,
            valuation != null ? valuation.valuationValue() : null,
            valuation != null ? valuation.navDate() : null,
            valuation != null ? valuation.premiumRate() : null,
            valuation != null ? valuation.navRefPremiumRate() : null,
            valuation != null ? valuation.spreadRate() : null,
            signal.signalLevel(),
            signal.signalReason(),
            quote.quoteTs(),
            valuation != null ? valuation.valuationTs() : null,
            stale,
            tradingSessionService.marketPhase(),
            quote.source().name().toLowerCase(Locale.ROOT)
        );
    }

    private Comparator<InstrumentOverview> buildComparator(String sortBy, String sortOrder) {
        Comparator<InstrumentOverview> comparator;
        String normalized = sortBy == null ? "signal" : sortBy;
        boolean asc = "asc".equalsIgnoreCase(sortOrder);
        switch (normalized) {
            case "premium":
                comparator = Comparator.comparing(
                    (InstrumentOverview row) -> deviationValue(row),
                    Comparator.nullsLast(Comparator.naturalOrder()));
                if (!asc) comparator = comparator.reversed();
                comparator = nullDeviationLast(comparator);
                break;
            case "turnover":
                comparator = Comparator.comparingLong(InstrumentOverview::turnover);
                if (!asc) comparator = comparator.reversed();
                break;
            case "spread":
                comparator = Comparator.comparing(
                    InstrumentOverview::spreadRate,
                    Comparator.nullsLast(Comparator.naturalOrder()));
                if (!asc) comparator = comparator.reversed();
                comparator = nullSpreadLast(comparator);
                break;
            case "changeRate":
                comparator = Comparator.comparingDouble(InstrumentOverview::changeRate);
                if (!asc) comparator = comparator.reversed();
                break;
            case "price":
                comparator = Comparator.comparingDouble(InstrumentOverview::price);
                if (!asc) comparator = comparator.reversed();
                break;
            case "name":
                comparator = Comparator.comparing(InstrumentOverview::name);
                if (!asc) comparator = comparator.reversed();
                break;
            case "code":
                comparator = Comparator.comparing(InstrumentOverview::code);
                if (!asc) comparator = comparator.reversed();
                break;
            case "signal":
            default:
                comparator = Comparator.comparingInt((InstrumentOverview row) -> signalRank(row.signalLevel()))
                    .thenComparing(
                        (InstrumentOverview row) -> deviationValue(row),
                        Comparator.nullsLast(Comparator.reverseOrder()));
                if (asc) comparator = comparator.reversed();
                break;
        }
        return comparator;
    }

    private Comparator<InstrumentOverview> nullDeviationLast(Comparator<InstrumentOverview> inner) {
        return (a, b) -> {
            boolean aN = deviationValue(a) == null;
            boolean bN = deviationValue(b) == null;
            if (aN && bN) return 0;
            if (aN) return 1;
            if (bN) return -1;
            return inner.compare(a, b);
        };
    }

    private Comparator<InstrumentOverview> nullSpreadLast(Comparator<InstrumentOverview> inner) {
        return (a, b) -> {
            boolean aN = a.spreadRate() == null;
            boolean bN = b.spreadRate() == null;
            if (aN && bN) return 0;
            if (aN) return 1;
            if (bN) return -1;
            return inner.compare(a, b);
        };
    }

    private Double deviationValue(InstrumentOverview row) {
        if (row.instrumentType() == InstrumentType.MONEY_ETF) {
            return null;
        }
        return row.premiumRate() != null ? row.premiumRate() : row.navRefPremiumRate();
    }

    private int signalRank(SignalLevel level) {
        List<SignalLevel> order = new ArrayList<>(List.of(
            SignalLevel.WATCH,
            SignalLevel.OBSERVE,
            SignalLevel.IGNORE,
            SignalLevel.INSUFFICIENT_DATA
        ));
        return order.indexOf(level);
    }

    private QuoteSnapshot fallbackQuote(String code) {
        return new QuoteSnapshot(code, 0, null, null, 0, 0, null, null, com.example.etfmonitor.domain.MarketSource.BACKUP, true);
    }

    private Double nullSafe(Double value) {
        return value == null ? Double.NEGATIVE_INFINITY : value;
    }
}
