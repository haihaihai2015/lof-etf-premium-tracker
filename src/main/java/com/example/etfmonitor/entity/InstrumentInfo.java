package com.example.etfmonitor.entity;

import com.example.etfmonitor.domain.InstrumentStatus;
import com.example.etfmonitor.domain.InstrumentType;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.Id;
import javax.persistence.Table;

@Entity
@Table(name = "instrument_info")
public class InstrumentInfo {

    @Id
    @Column(length = 16, nullable = false, unique = true)
    private String code;

    @Column(length = 64, nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "instrument_type", length = 16, nullable = false)
    private InstrumentType instrumentType;

    @Column(length = 8, nullable = false)
    private String market;

    @Column(name = "fund_company", length = 64)
    private String fundCompany;

    @Column(name = "index_name", length = 64)
    private String indexName;

    @Column(name = "minimum_subscription_unit")
    private Long minimumSubscriptionUnit;

    @Column(name = "can_subscribe", nullable = false)
    private boolean canSubscribe;

    @Column(name = "can_redeem", nullable = false)
    private boolean canRedeem;

    @Column(name = "fund_type", length = 32)
    private String fundType;

    @Column(name = "daily_purchase_limit")
    private Long dailyPurchaseLimit;

    @Enumerated(EnumType.STRING)
    @Column(length = 16, nullable = false)
    private InstrumentStatus status;

    protected InstrumentInfo() {
    }

    public InstrumentInfo(String code, String name, InstrumentType instrumentType, String market, String fundCompany,
                          String indexName, boolean canSubscribe, boolean canRedeem, InstrumentStatus status) {
        this(code, name, instrumentType, market, fundCompany, indexName, null, canSubscribe, canRedeem, status);
    }

    public InstrumentInfo(String code, String name, InstrumentType instrumentType, String market, String fundCompany,
                          String indexName, Long minimumSubscriptionUnit, boolean canSubscribe, boolean canRedeem, InstrumentStatus status) {
        this.code = code;
        this.name = name;
        this.instrumentType = instrumentType;
        this.market = market;
        this.fundCompany = fundCompany;
        this.indexName = indexName;
        this.minimumSubscriptionUnit = minimumSubscriptionUnit;
        this.canSubscribe = canSubscribe;
        this.canRedeem = canRedeem;
        this.status = status;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public InstrumentType getInstrumentType() {
        return instrumentType;
    }

    public String getMarket() {
        return market;
    }

    public String getFundCompany() {
        return fundCompany;
    }

    public String getIndexName() {
        return indexName;
    }

    public Long getMinimumSubscriptionUnit() {
        return minimumSubscriptionUnit;
    }

    public boolean isCanSubscribe() {
        return canSubscribe;
    }

    public boolean isCanRedeem() {
        return canRedeem;
    }

    public InstrumentStatus getStatus() {
        return status;
    }

    public void setFundCompany(String fundCompany) {
        this.fundCompany = fundCompany;
    }

    public void setIndexName(String indexName) {
        this.indexName = indexName;
    }

    public void setMinimumSubscriptionUnit(Long minimumSubscriptionUnit) {
        this.minimumSubscriptionUnit = minimumSubscriptionUnit;
    }

    public void setCanSubscribe(boolean canSubscribe) {
        this.canSubscribe = canSubscribe;
    }

    public void setCanRedeem(boolean canRedeem) {
        this.canRedeem = canRedeem;
    }

    public String getFundType() {
        return fundType;
    }

    public void setFundType(String fundType) {
        this.fundType = fundType;
    }

    public Long getDailyPurchaseLimit() {
        return dailyPurchaseLimit;
    }

    public void setDailyPurchaseLimit(Long dailyPurchaseLimit) {
        this.dailyPurchaseLimit = dailyPurchaseLimit;
    }
}
