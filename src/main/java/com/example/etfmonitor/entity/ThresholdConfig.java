package com.example.etfmonitor.entity;

import com.example.etfmonitor.domain.InstrumentType;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Table;

@Entity
@Table(name = "threshold_config")
public class ThresholdConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "instrument_type", length = 16, nullable = false, unique = true)
    private InstrumentType instrumentType;

    @Column(name = "premium_threshold", nullable = false)
    private double premiumThreshold;

    @Column(name = "spread_threshold", nullable = false)
    private double spreadThreshold;

    @Column(name = "turnover_threshold", nullable = false)
    private long turnoverThreshold;

    @Column(name = "watch_enabled", nullable = false)
    private boolean watchEnabled;

    protected ThresholdConfig() {
    }

    public ThresholdConfig(InstrumentType instrumentType, double premiumThreshold, double spreadThreshold,
                           long turnoverThreshold, boolean watchEnabled) {
        this.instrumentType = instrumentType;
        this.premiumThreshold = premiumThreshold;
        this.spreadThreshold = spreadThreshold;
        this.turnoverThreshold = turnoverThreshold;
        this.watchEnabled = watchEnabled;
    }

    public InstrumentType getInstrumentType() {
        return instrumentType;
    }

    public double getPremiumThreshold() {
        return premiumThreshold;
    }

    public double getSpreadThreshold() {
        return spreadThreshold;
    }

    public long getTurnoverThreshold() {
        return turnoverThreshold;
    }

    public boolean isWatchEnabled() {
        return watchEnabled;
    }
}
