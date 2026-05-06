package com.example.etfmonitor.service;

import com.example.etfmonitor.domain.InstrumentType;
import com.example.etfmonitor.domain.ValuationType;
import com.example.etfmonitor.entity.InstrumentInfo;
import com.example.etfmonitor.entity.MinuteSnapshot;
import com.example.etfmonitor.model.QuoteSnapshot;
import com.example.etfmonitor.model.SignalSnapshot;
import com.example.etfmonitor.model.ValuationSnapshot;
import com.example.etfmonitor.repository.InstrumentInfoRepository;
import com.example.etfmonitor.repository.MinuteSnapshotRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class MarketDataRefreshService {

    private final InstrumentInfoRepository instrumentInfoRepository;
    private final MinuteSnapshotRepository minuteSnapshotRepository;
    private final MarketDataOrchestrator marketDataOrchestrator;
    private final RealtimeStateStore realtimeStateStore;
    private final SignalRuleService signalRuleService;
    private final TradingSessionService tradingSessionService;
    private final InstrumentQueryService instrumentQueryService;

    public MarketDataRefreshService(InstrumentInfoRepository instrumentInfoRepository,
                                    MinuteSnapshotRepository minuteSnapshotRepository,
                                    MarketDataOrchestrator marketDataOrchestrator,
                                    RealtimeStateStore realtimeStateStore,
                                    SignalRuleService signalRuleService,
                                    TradingSessionService tradingSessionService,
                                    InstrumentQueryService instrumentQueryService) {
        this.instrumentInfoRepository = instrumentInfoRepository;
        this.minuteSnapshotRepository = minuteSnapshotRepository;
        this.marketDataOrchestrator = marketDataOrchestrator;
        this.realtimeStateStore = realtimeStateStore;
        this.signalRuleService = signalRuleService;
        this.tradingSessionService = tradingSessionService;
        this.instrumentQueryService = instrumentQueryService;
    }

    @Scheduled(fixedDelayString = "${app.schedule.market-refresh-ms}", initialDelay = 1000L)
    public void refreshRealtimeData() {
        List<InstrumentInfo> instruments = instrumentInfoRepository.findAll();
        if (instruments.isEmpty()) {
            return;
        }

        Map<String, QuoteSnapshot> quotes = marketDataOrchestrator.fetchQuotes(instruments);
        Map<String, ValuationSnapshot> valuations = calculateValuations(
            instruments,
            quotes,
            marketDataOrchestrator.fetchValuations(instruments, quotes)
        );
        Map<String, SignalSnapshot> signals = signalRuleService.buildSignals(
            instruments,
            quotes,
            valuations,
            tradingSessionService.isContinuousTrading()
        );

        realtimeStateStore.replaceQuotes(quotes);
        realtimeStateStore.replaceValuations(valuations);
        realtimeStateStore.replaceSignals(signals);
        instrumentQueryService.precomputeOverviews();
    }

    @Scheduled(fixedDelayString = "${app.schedule.minute-snapshot-ms}", initialDelay = 5000L)
    public void persistMinuteSnapshots() {
        List<InstrumentInfo> instruments = instrumentInfoRepository.findAll();
        LocalDateTime snapshotMinute = tradingSessionService.truncateToMinute(LocalDateTime.now());

        List<String> existingCodes = minuteSnapshotRepository
            .findAllBySnapshotMinute(snapshotMinute).stream()
            .map(MinuteSnapshot::getCode)
            .toList();
        java.util.Set<String> existingSet = new java.util.HashSet<>(existingCodes);

        List<MinuteSnapshot> batch = new java.util.ArrayList<>();
        for (InstrumentInfo instrument : instruments) {
            if (existingSet.contains(instrument.getCode())) {
                continue;
            }
            QuoteSnapshot quote = realtimeStateStore.getQuote(instrument.getCode()).orElse(null);
            ValuationSnapshot valuation = realtimeStateStore.getValuation(instrument.getCode()).orElse(null);
            if (quote == null) {
                continue;
            }
            batch.add(new MinuteSnapshot(
                instrument.getCode(),
                snapshotMinute,
                quote.price(),
                quote.turnover(),
                quote.changeRate(),
                valuation != null ? valuation.premiumRate() : null,
                valuation != null ? valuation.navRefPremiumRate() : null,
                valuation != null ? valuation.spreadRate() : null,
                valuation != null ? valuation.valuationValue() : null,
                valuation != null ? valuation.valuationType() : ValuationType.NONE
            ));
        }
        if (!batch.isEmpty()) {
            minuteSnapshotRepository.saveAll(batch);
        }
    }

    @Scheduled(cron = "0 15 9 * * MON-FRI", zone = "${app.market.zone-id}")
    public void refreshReferenceData() {
        // Reserved for a daily instrument sync job.
    }

    private Map<String, ValuationSnapshot> calculateValuations(List<InstrumentInfo> instruments,
                                                               Map<String, QuoteSnapshot> quotes,
                                                               Map<String, ValuationSnapshot> rawValuations) {
        Map<String, ValuationSnapshot> results = new HashMap<>();
        for (InstrumentInfo instrument : instruments) {
            QuoteSnapshot quote = quotes.get(instrument.getCode());
            if (quote == null) {
                continue;
            }

            Double spreadRate = quote.price() > 0 && quote.ask1() != null && quote.bid1() != null
                ? round((quote.ask1() - quote.bid1()) / quote.price())
                : null;

            if (instrument.getInstrumentType() == InstrumentType.MONEY_ETF) {
                results.put(instrument.getCode(), new ValuationSnapshot(
                    instrument.getCode(),
                    ValuationType.NONE,
                    null,
                    null,
                    null,
                    null,
                    null,
                    spreadRate
                ));
                continue;
            }

            ValuationSnapshot raw = rawValuations.get(instrument.getCode());
            if (raw == null || raw.valuationValue() == null || raw.valuationType() == ValuationType.NONE) {
                results.put(instrument.getCode(), new ValuationSnapshot(
                    instrument.getCode(),
                    ValuationType.NONE,
                    null,
                    null,
                    null,
                    null,
                    null,
                    spreadRate
                ));
                continue;
            }

            // Safety guard: if price/NAV ratio is unreasonable (>10x), the instrument is likely
            // a misclassified money-market ETF whose unit NAV is not comparable to its trading price.
            if (raw.valuationValue() > 0 && quote.price() / raw.valuationValue() > 10) {
                results.put(instrument.getCode(), new ValuationSnapshot(
                    instrument.getCode(),
                    ValuationType.NONE,
                    null,
                    null,
                    null,
                    null,
                    null,
                    spreadRate
                ));
                continue;
            }

            if (instrument.getInstrumentType() == InstrumentType.ETF && raw.valuationType() == ValuationType.IOPV) {
                double premiumRate = round((quote.price() - raw.valuationValue()) / raw.valuationValue());
                results.put(instrument.getCode(), new ValuationSnapshot(
                    instrument.getCode(),
                    ValuationType.IOPV,
                    raw.valuationValue(),
                    raw.valuationTs(),
                    raw.navDate(),
                    premiumRate,
                    null,
                    spreadRate
                ));
                continue;
            }

            double navRefPremiumRate = round((quote.price() - raw.valuationValue()) / raw.valuationValue());
            results.put(instrument.getCode(), new ValuationSnapshot(
                instrument.getCode(),
                raw.valuationType(),
                raw.valuationValue(),
                raw.valuationTs(),
                raw.navDate(),
                null,
                navRefPremiumRate,
                spreadRate
            ));
        }
        return results;
    }

    private double round(double value) {
        return Math.round(value * 1_000_000.0) / 1_000_000.0;
    }
}
