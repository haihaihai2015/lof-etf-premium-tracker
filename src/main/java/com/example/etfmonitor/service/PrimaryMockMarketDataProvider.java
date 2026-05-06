package com.example.etfmonitor.service;

import com.example.etfmonitor.domain.InstrumentType;
import com.example.etfmonitor.domain.MarketSource;
import com.example.etfmonitor.domain.ValuationType;
import com.example.etfmonitor.entity.InstrumentInfo;
import com.example.etfmonitor.model.QuoteSnapshot;
import com.example.etfmonitor.model.ValuationSnapshot;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

@Component("primaryMarketDataProvider")
@ConditionalOnProperty(name = "app.market.provider-mode", havingValue = "mock")
public class PrimaryMockMarketDataProvider implements MarketDataProvider {

    @Override
    public Map<String, QuoteSnapshot> fetchQuotes(Collection<InstrumentInfo> instruments) {
        Map<String, QuoteSnapshot> result = new HashMap<>();
        LocalDateTime now = LocalDateTime.now();
        int tick = now.getSecond() / 5;
        for (InstrumentInfo instrument : instruments) {
            double base = basePrice(instrument.getCode(), instrument.getInstrumentType());
            double wave = ((instrument.getCode().hashCode() + tick * 13) % 17) / 1000.0;
            double changeRate = ((instrument.getCode().hashCode() >> 2) % 9) / 1000.0 - 0.004 + wave / 3;
            double price = round(base * (1 + changeRate));
            double spread = instrument.getInstrumentType() == InstrumentType.ETF ? 0.0008 + (tick % 3) * 0.0002 : 0.0012;
            double bid1 = round(price * (1 - spread / 2));
            double ask1 = round(price * (1 + spread / 2));
            long turnover = turnoverBase(instrument.getCode(), instrument.getInstrumentType()) + tick * 135_000L;
            result.put(instrument.getCode(), new QuoteSnapshot(
                instrument.getCode(),
                price,
                bid1,
                ask1,
                turnover,
                round(changeRate),
                now,
                now,
                MarketSource.BACKUP,
                false
            ));
        }
        return result;
    }

    @Override
    public Map<String, ValuationSnapshot> fetchValuations(Collection<InstrumentInfo> instruments, Map<String, QuoteSnapshot> quotes) {
        Map<String, ValuationSnapshot> result = new HashMap<>();
        LocalDateTime now = LocalDateTime.now();
        int tick = now.getSecond() / 5;
        for (InstrumentInfo instrument : instruments) {
            QuoteSnapshot quote = quotes.get(instrument.getCode());
            if (quote == null) {
                continue;
            }
            if (instrument.getInstrumentType() == InstrumentType.ETF) {
                double iopv = round(quote.price() * (1 - 0.004 + ((tick % 4) * 0.0015)));
                result.put(instrument.getCode(), new ValuationSnapshot(
                    instrument.getCode(),
                    ValuationType.IOPV,
                    iopv,
                    now,
                    LocalDate.now(),
                    null,
                    null,
                    null
                ));
            } else {
                double nav = round(basePrice(instrument.getCode(), instrument.getInstrumentType()) * (1 + ((tick % 5) - 2) / 200.0));
                result.put(instrument.getCode(), new ValuationSnapshot(
                    instrument.getCode(),
                    ValuationType.NAV_REF,
                    nav,
                    now.minusDays(1),
                    LocalDate.now().minusDays(1),
                    null,
                    null,
                    null
                ));
            }
        }
        return result;
    }

    @Override
    public boolean isHealthy() {
        return true;
    }

    private double basePrice(String code, InstrumentType type) {
        int seed = Math.abs(code.hashCode());
        if (type == InstrumentType.ETF) {
            return 0.9 + (seed % 280) / 100.0;
        }
        return 0.8 + (seed % 220) / 100.0;
    }

    private long turnoverBase(String code, InstrumentType type) {
        int seed = Math.abs(code.hashCode());
        return (type == InstrumentType.ETF ? 6_000_000L : 1_500_000L) + (seed % 80) * 100_000L;
    }

    private double round(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }
}
