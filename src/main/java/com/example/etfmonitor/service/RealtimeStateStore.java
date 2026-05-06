package com.example.etfmonitor.service;

import com.example.etfmonitor.model.QuoteSnapshot;
import com.example.etfmonitor.model.SignalSnapshot;
import com.example.etfmonitor.model.ValuationSnapshot;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class RealtimeStateStore {

    private final Map<String, QuoteSnapshot> quotes = new ConcurrentHashMap<>();
    private final Map<String, ValuationSnapshot> valuations = new ConcurrentHashMap<>();
    private final Map<String, SignalSnapshot> signals = new ConcurrentHashMap<>();

    public void replaceQuotes(Map<String, QuoteSnapshot> latestQuotes) {
        quotes.clear();
        quotes.putAll(latestQuotes);
    }

    public void replaceValuations(Map<String, ValuationSnapshot> latestValuations) {
        valuations.clear();
        valuations.putAll(latestValuations);
    }

    public void replaceSignals(Map<String, SignalSnapshot> latestSignals) {
        signals.clear();
        signals.putAll(latestSignals);
    }

    public Optional<QuoteSnapshot> getQuote(String code) {
        return Optional.ofNullable(quotes.get(code));
    }

    public Optional<ValuationSnapshot> getValuation(String code) {
        return Optional.ofNullable(valuations.get(code));
    }

    public Optional<SignalSnapshot> getSignal(String code) {
        return Optional.ofNullable(signals.get(code));
    }

    public Collection<QuoteSnapshot> allQuotes() {
        return quotes.values();
    }
}
