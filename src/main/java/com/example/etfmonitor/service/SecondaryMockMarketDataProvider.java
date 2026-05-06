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

@Component("secondaryMarketDataProvider")
@ConditionalOnProperty(name = "app.market.provider-mode", havingValue = "mock")
public class SecondaryMockMarketDataProvider implements MarketDataProvider {

    @Override
    public Map<String, QuoteSnapshot> fetchQuotes(Collection<InstrumentInfo> instruments) {
        Map<String, QuoteSnapshot> result = new HashMap<>();
        LocalDateTime now = LocalDateTime.now();
        int tick = now.getMinute() % 12;
        for (InstrumentInfo instrument : instruments) {
            double base = (instrument.getInstrumentType() == InstrumentType.ETF ? 1.1 : 0.95) + (Math.abs(instrument.getCode().hashCode()) % 200) / 100.0;
            double changeRate = ((tick - 6) / 1200.0);
            double price = round(base * (1 + changeRate));
            double bid1 = round(price * 0.9992);
            double ask1 = round(price * 1.0008);
            long turnover = (instrument.getInstrumentType() == InstrumentType.ETF ? 5_000_000L : 1_200_000L) + tick * 100_000L;
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
        for (InstrumentInfo instrument : instruments) {
            QuoteSnapshot quote = quotes.get(instrument.getCode());
            if (quote == null) {
                continue;
            }
            ValuationType valuationType = instrument.getInstrumentType() == InstrumentType.ETF ? ValuationType.IOPV : ValuationType.NAV_REF;
            double valuation = instrument.getInstrumentType() == InstrumentType.ETF
                ? round(quote.price() * 0.997)
                : round(quote.price() * 0.988);
            result.put(instrument.getCode(), new ValuationSnapshot(
                instrument.getCode(),
                valuationType,
                valuation,
                now.minusSeconds(3),
                instrument.getInstrumentType() == InstrumentType.ETF ? LocalDate.now() : LocalDate.now().minusDays(1),
                null,
                null,
                null
            ));
        }
        return result;
    }

    @Override
    public boolean isHealthy() {
        return true;
    }

    private double round(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }
}
