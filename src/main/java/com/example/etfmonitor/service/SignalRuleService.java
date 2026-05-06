package com.example.etfmonitor.service;

import com.example.etfmonitor.domain.InstrumentStatus;
import com.example.etfmonitor.domain.InstrumentType;
import com.example.etfmonitor.domain.SignalLevel;
import com.example.etfmonitor.domain.ValuationType;
import com.example.etfmonitor.entity.InstrumentInfo;
import com.example.etfmonitor.entity.ThresholdConfig;
import com.example.etfmonitor.model.QuoteSnapshot;
import com.example.etfmonitor.model.SignalSnapshot;
import com.example.etfmonitor.model.ValuationSnapshot;
import com.example.etfmonitor.repository.ThresholdConfigRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class SignalRuleService {

    private final ThresholdConfigRepository thresholdConfigRepository;

    public SignalRuleService(ThresholdConfigRepository thresholdConfigRepository) {
        this.thresholdConfigRepository = thresholdConfigRepository;
    }

    public Map<String, SignalSnapshot> buildSignals(List<InstrumentInfo> instruments,
                                                    Map<String, QuoteSnapshot> quotes,
                                                    Map<String, ValuationSnapshot> valuations,
                                                    boolean tradingOpen) {
        Map<InstrumentType, ThresholdConfig> thresholds = thresholdConfigRepository.findAll()
            .stream()
            .collect(Collectors.toMap(
                ThresholdConfig::getInstrumentType,
                config -> config,
                (left, right) -> left,
                () -> new EnumMap<>(InstrumentType.class)
            ));

        return instruments.stream().collect(Collectors.toMap(
            InstrumentInfo::getCode,
            instrument -> buildSignal(
                instrument,
                quotes.get(instrument.getCode()),
                valuations.get(instrument.getCode()),
                thresholds.get(instrument.getInstrumentType()),
                tradingOpen
            )
        ));
    }

    private SignalSnapshot buildSignal(InstrumentInfo instrument,
                                       QuoteSnapshot quote,
                                       ValuationSnapshot valuation,
                                       ThresholdConfig threshold,
                                       boolean tradingOpen) {
        LocalDateTime now = LocalDateTime.now();
        if (!tradingOpen || instrument.getStatus() != InstrumentStatus.TRADING || quote == null) {
            return new SignalSnapshot(instrument.getCode(), SignalLevel.INSUFFICIENT_DATA, "非连续交易时段或行情缺失", now);
        }

        if (instrument.getInstrumentType() == InstrumentType.MONEY_ETF) {
            return new SignalSnapshot(instrument.getCode(), SignalLevel.IGNORE, "货币ETF不参与折溢价比较", now);
        }
        if (instrument.getInstrumentType() == InstrumentType.ETF) {
            return buildEtfSignal(instrument, quote, valuation, threshold, now);
        }
        return buildLofSignal(instrument, quote, valuation, threshold, now);
    }

    private SignalSnapshot buildEtfSignal(InstrumentInfo instrument,
                                          QuoteSnapshot quote,
                                          ValuationSnapshot valuation,
                                          ThresholdConfig threshold,
                                          LocalDateTime now) {
        if (valuation == null || valuation.valuationValue() == null) {
            return new SignalSnapshot(instrument.getCode(), SignalLevel.INSUFFICIENT_DATA, "缺少估值参考", now);
        }
        if (valuation.valuationType() != ValuationType.IOPV || valuation.premiumRate() == null) {
            return new SignalSnapshot(instrument.getCode(), SignalLevel.INSUFFICIENT_DATA, "仅有参考净值，缺少实时 IOPV", now);
        }
        if (valuation.spreadRate() == null || quote.turnover() <= 0) {
            return new SignalSnapshot(instrument.getCode(), SignalLevel.INSUFFICIENT_DATA, "盘口或成交额不足", now);
        }

        double premium = valuation.premiumRate();
        if (threshold != null && threshold.isWatchEnabled()
            && premium > threshold.getPremiumThreshold()
            && valuation.spreadRate() < threshold.getSpreadThreshold()
            && quote.turnover() > threshold.getTurnoverThreshold()) {
            return new SignalSnapshot(instrument.getCode(), SignalLevel.WATCH, "溢价偏高且点差合格，可继续跟踪", now);
        }

        if (Math.abs(premium) > (threshold != null ? threshold.getPremiumThreshold() * 0.5 : 0.002)) {
            return new SignalSnapshot(instrument.getCode(), SignalLevel.OBSERVE, "存在价格偏离，建议观察流动性", now);
        }

        return new SignalSnapshot(instrument.getCode(), SignalLevel.IGNORE, "偏离有限，暂不优先跟踪", now);
    }

    private SignalSnapshot buildLofSignal(InstrumentInfo instrument,
                                          QuoteSnapshot quote,
                                          ValuationSnapshot valuation,
                                          ThresholdConfig threshold,
                                          LocalDateTime now) {
        if (valuation == null || valuation.valuationValue() == null || valuation.navRefPremiumRate() == null) {
            return new SignalSnapshot(instrument.getCode(), SignalLevel.INSUFFICIENT_DATA, "缺少最近净值参考", now);
        }
        if (quote.turnover() <= 0) {
            return new SignalSnapshot(instrument.getCode(), SignalLevel.INSUFFICIENT_DATA, "成交额不足", now);
        }

        double deviation = valuation.navRefPremiumRate();
        double observeThreshold = threshold != null ? threshold.getPremiumThreshold() * 0.5 : 0.005;
        if (Math.abs(deviation) >= observeThreshold) {
            return new SignalSnapshot(instrument.getCode(), SignalLevel.OBSERVE, "相对最近净值偏离较明显，适合研究观察", now);
        }

        return new SignalSnapshot(instrument.getCode(), SignalLevel.IGNORE, "相对最近净值偏离有限", now);
    }
}
