package com.example.etfmonitor.entity;

import com.example.etfmonitor.domain.ValuationType;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Index;
import javax.persistence.Table;
import java.time.LocalDateTime;

@Entity
@Table(name = "minute_snapshot", indexes = {
    @Index(name = "idx_snapshot_code_minute", columnList = "code,snapshot_minute")
})
public class MinuteSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 16, nullable = false)
    private String code;

    @Column(name = "snapshot_minute", nullable = false)
    private LocalDateTime snapshotMinute;

    @Column(nullable = false)
    private double price;

    @Column(nullable = false)
    private long turnover;

    @Column(name = "change_rate")
    private Double changeRate;

    @Column(name = "premium_rate")
    private Double premiumRate;

    @Column(name = "nav_ref_premium_rate")
    private Double navRefPremiumRate;

    @Column(name = "spread_rate")
    private Double spreadRate;

    @Column(name = "valuation_value")
    private Double valuationValue;

    @Enumerated(EnumType.STRING)
    @Column(name = "valuation_type", length = 16, nullable = false)
    private ValuationType valuationType;

    protected MinuteSnapshot() {
    }

    public MinuteSnapshot(String code, LocalDateTime snapshotMinute, double price, long turnover, Double changeRate,
                          Double premiumRate, Double navRefPremiumRate, Double spreadRate, Double valuationValue,
                          ValuationType valuationType) {
        this.code = code;
        this.snapshotMinute = snapshotMinute;
        this.price = price;
        this.turnover = turnover;
        this.changeRate = changeRate;
        this.premiumRate = premiumRate;
        this.navRefPremiumRate = navRefPremiumRate;
        this.spreadRate = spreadRate;
        this.valuationValue = valuationValue;
        this.valuationType = valuationType;
    }

    public String getCode() {
        return code;
    }

    public LocalDateTime getSnapshotMinute() {
        return snapshotMinute;
    }

    public double getPrice() {
        return price;
    }

    public long getTurnover() {
        return turnover;
    }

    public Double getChangeRate() {
        return changeRate;
    }

    public Double getPremiumRate() {
        return premiumRate;
    }

    public Double getNavRefPremiumRate() {
        return navRefPremiumRate;
    }

    public Double getSpreadRate() {
        return spreadRate;
    }

    public Double getValuationValue() {
        return valuationValue;
    }

    public ValuationType getValuationType() {
        return valuationType;
    }
}
