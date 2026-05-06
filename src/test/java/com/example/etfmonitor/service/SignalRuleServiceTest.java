package com.example.etfmonitor.service;

import com.example.etfmonitor.domain.InstrumentStatus;
import com.example.etfmonitor.domain.InstrumentType;
import com.example.etfmonitor.domain.MarketSource;
import com.example.etfmonitor.domain.SignalLevel;
import com.example.etfmonitor.domain.ValuationType;
import com.example.etfmonitor.entity.InstrumentInfo;
import com.example.etfmonitor.entity.ThresholdConfig;
import com.example.etfmonitor.model.QuoteSnapshot;
import com.example.etfmonitor.model.SignalSnapshot;
import com.example.etfmonitor.model.ValuationSnapshot;
import com.example.etfmonitor.repository.ThresholdConfigRepository;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

class SignalRuleServiceTest {

    @Test
    void etfShouldBecomeWatchWhenThresholdsMatch() {
        ThresholdConfigRepository repository = Mockito.mock(ThresholdConfigRepository.class);
        when(repository.findAll()).thenReturn(List.of(new ThresholdConfig(InstrumentType.ETF, 0.005, 0.0015, 5_000_000L, true)));
        SignalRuleService service = new SignalRuleService(repository);
        InstrumentInfo instrument = new InstrumentInfo("510300", "沪深300ETF", InstrumentType.ETF, "SH", "华泰柏瑞基金", "沪深300", true, true, InstrumentStatus.TRADING);
        QuoteSnapshot quote = new QuoteSnapshot("510300", 3.81, 3.808, 3.812, 8_000_000L, 0.003, LocalDateTime.now(), LocalDateTime.now(), MarketSource.BACKUP, false);
        ValuationSnapshot valuation = new ValuationSnapshot("510300", ValuationType.IOPV, 3.79, LocalDateTime.now(), LocalDate.now(), 0.005277, null, 0.00105);

        Map<String, SignalSnapshot> result = service.buildSignals(List.of(instrument), Map.of("510300", quote), Map.of("510300", valuation), true);

        assertThat(result.get("510300").signalLevel()).isEqualTo(SignalLevel.WATCH);
    }

    @Test
    void lofShouldNotUseEtfWatchRule() {
        ThresholdConfigRepository repository = Mockito.mock(ThresholdConfigRepository.class);
        when(repository.findAll()).thenReturn(List.of(new ThresholdConfig(InstrumentType.LOF, 0.01, 0.002, 1_500_000L, false)));
        SignalRuleService service = new SignalRuleService(repository);
        InstrumentInfo instrument = new InstrumentInfo("160633", "券商LOF", InstrumentType.LOF, "SZ", "鹏华基金", "证券公司", true, true, InstrumentStatus.TRADING);
        QuoteSnapshot quote = new QuoteSnapshot("160633", 1.03, 1.029, 1.031, 2_000_000L, 0.002, LocalDateTime.now(), LocalDateTime.now(), MarketSource.BACKUP, false);
        ValuationSnapshot valuation = new ValuationSnapshot("160633", ValuationType.NAV_REF, 1.0, LocalDateTime.now(), LocalDate.now().minusDays(1), null, 0.03, null);

        Map<String, SignalSnapshot> result = service.buildSignals(List.of(instrument), Map.of("160633", quote), Map.of("160633", valuation), true);

        assertThat(result.get("160633").signalLevel()).isEqualTo(SignalLevel.OBSERVE);
    }

    @Test
    void moneyEtfShouldBeIgnored() {
        ThresholdConfigRepository repository = Mockito.mock(ThresholdConfigRepository.class);
        when(repository.findAll()).thenReturn(List.of(new ThresholdConfig(InstrumentType.MONEY_ETF, 0.0, 0.0005, 10_000_000L, false)));
        SignalRuleService service = new SignalRuleService(repository);
        InstrumentInfo instrument = new InstrumentInfo("511830", "华泰货币ETF", InstrumentType.MONEY_ETF, "SH", null, null, true, true, InstrumentStatus.TRADING);
        QuoteSnapshot quote = new QuoteSnapshot("511830", 100.0, 100.0, 100.001, 20_000_000L, 0.0, LocalDateTime.now(), LocalDateTime.now(), MarketSource.BACKUP, false);

        Map<String, SignalSnapshot> result = service.buildSignals(List.of(instrument), Map.of("511830", quote), Map.of(), true);

        assertThat(result.get("511830").signalLevel()).isEqualTo(SignalLevel.IGNORE);
    }
}
