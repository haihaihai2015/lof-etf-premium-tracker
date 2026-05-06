package com.example.etfmonitor.service;

import com.example.etfmonitor.entity.InstrumentInfo;
import com.example.etfmonitor.model.QuoteSnapshot;
import com.example.etfmonitor.model.ValuationSnapshot;

import java.util.Collection;
import java.util.Map;

public interface MarketDataProvider {

    Map<String, QuoteSnapshot> fetchQuotes(Collection<InstrumentInfo> instruments);

    Map<String, ValuationSnapshot> fetchValuations(Collection<InstrumentInfo> instruments, Map<String, QuoteSnapshot> quotes);

    boolean isHealthy();
}
