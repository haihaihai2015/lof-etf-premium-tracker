package com.example.etfmonitor.service;

import com.example.etfmonitor.domain.InstrumentType;
import com.example.etfmonitor.entity.ThresholdConfig;
import com.example.etfmonitor.repository.ThresholdConfigRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class BootstrapDataLoader implements CommandLineRunner {

    private final ThresholdConfigRepository thresholdConfigRepository;
    private final MarketDataRefreshService marketDataRefreshService;
    private final InstrumentUniverseSyncService instrumentUniverseSyncService;

    public BootstrapDataLoader(ThresholdConfigRepository thresholdConfigRepository,
                               MarketDataRefreshService marketDataRefreshService,
                               InstrumentUniverseSyncService instrumentUniverseSyncService) {
        this.thresholdConfigRepository = thresholdConfigRepository;
        this.marketDataRefreshService = marketDataRefreshService;
        this.instrumentUniverseSyncService = instrumentUniverseSyncService;
    }

    @Override
    public void run(String... args) {
        instrumentUniverseSyncService.syncUniverse();

        if (thresholdConfigRepository.count() == 0) {
            thresholdConfigRepository.saveAll(List.of(
                new ThresholdConfig(InstrumentType.ETF, 0.005, 0.0015, 5_000_000L, true),
                new ThresholdConfig(InstrumentType.LOF, 0.01, 0.002, 1_500_000L, false),
                new ThresholdConfig(InstrumentType.MONEY_ETF, 0.0, 0.0005, 10_000_000L, false)
            ));
        }

        marketDataRefreshService.refreshRealtimeData();
        marketDataRefreshService.persistMinuteSnapshots();
    }
}
