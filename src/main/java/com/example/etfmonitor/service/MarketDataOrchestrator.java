package com.example.etfmonitor.service;

import com.example.etfmonitor.entity.InstrumentInfo;
import com.example.etfmonitor.model.QuoteSnapshot;
import com.example.etfmonitor.model.ValuationSnapshot;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;

@Service
public class MarketDataOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(MarketDataOrchestrator.class);

    private final MarketDataProvider primaryProvider;
    private final MarketDataProvider secondaryProvider;

    public MarketDataOrchestrator(@Qualifier("primaryMarketDataProvider") MarketDataProvider primaryProvider,
                                  @Qualifier("secondaryMarketDataProvider") ObjectProvider<MarketDataProvider> secondaryProvider) {
        this.primaryProvider = primaryProvider;
        this.secondaryProvider = secondaryProvider.getIfAvailable();
    }

    public Map<String, QuoteSnapshot> fetchQuotes(Collection<InstrumentInfo> instruments) {
        try {
            Map<String, QuoteSnapshot> quotes = primaryProvider.fetchQuotes(instruments);
            if (!quotes.isEmpty() || secondaryProvider == null) {
                return quotes;
            }
            log.warn("Primary market data provider returned no quotes; trying fallback provider.");
        } catch (Exception ignored) {
            log.warn("Primary market data provider failed to fetch quotes.", ignored);
        }
        if (secondaryProvider == null) {
            return Collections.emptyMap();
        }
        return secondaryProvider.fetchQuotes(instruments);
    }

    public Map<String, ValuationSnapshot> fetchValuations(Collection<InstrumentInfo> instruments,
                                                          Map<String, QuoteSnapshot> quotes) {
        try {
            Map<String, ValuationSnapshot> valuations = primaryProvider.fetchValuations(instruments, quotes);
            if (!valuations.isEmpty() || secondaryProvider == null) {
                return valuations;
            }
            log.warn("Primary market data provider returned no valuations; trying fallback provider.");
        } catch (Exception ignored) {
            log.warn("Primary market data provider failed to fetch valuations.", ignored);
        }
        if (secondaryProvider == null) {
            return Collections.emptyMap();
        }
        return secondaryProvider.fetchValuations(instruments, quotes);
    }
}
