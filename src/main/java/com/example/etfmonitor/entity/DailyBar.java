package com.example.etfmonitor.entity;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.IdClass;
import javax.persistence.Index;
import javax.persistence.Table;
import java.time.LocalDate;

@Entity
@Table(name = "daily_bar", indexes = {
    @Index(name = "idx_daily_bar_date", columnList = "trade_date"),
    @Index(name = "idx_daily_bar_code", columnList = "code")
})
@IdClass(DailyBarId.class)
public class DailyBar {

    @Id
    @Column(length = 16, nullable = false)
    private String code;

    @Id
    @Column(name = "trade_date", nullable = false)
    private LocalDate tradeDate;

    @Column(name = "open_price")
    private Double openPrice;

    @Column(name = "close_price")
    private Double closePrice;

    @Column(name = "high_price")
    private Double highPrice;

    @Column(name = "low_price")
    private Double lowPrice;

    private Long volume;

    private Long turnover;

    private Double nav;

    @Column(name = "premium_rate")
    private Double premiumRate;

    @Column(name = "can_subscribe")
    private Boolean canSubscribe;

    @Column(name = "can_redeem")
    private Boolean canRedeem;

    protected DailyBar() {
    }

    public DailyBar(String code, LocalDate tradeDate, Double openPrice, Double closePrice,
                    Double highPrice, Double lowPrice, Long volume, Long turnover,
                    Double nav, Double premiumRate) {
        this.code = code;
        this.tradeDate = tradeDate;
        this.openPrice = openPrice;
        this.closePrice = closePrice;
        this.highPrice = highPrice;
        this.lowPrice = lowPrice;
        this.volume = volume;
        this.turnover = turnover;
        this.nav = nav;
        this.premiumRate = premiumRate;
    }

    public String getCode() { return code; }
    public LocalDate getTradeDate() { return tradeDate; }
    public Double getOpenPrice() { return openPrice; }
    public Double getClosePrice() { return closePrice; }
    public Double getHighPrice() { return highPrice; }
    public Double getLowPrice() { return lowPrice; }
    public Long getVolume() { return volume; }
    public Long getTurnover() { return turnover; }
    public Double getNav() { return nav; }
    public Double getPremiumRate() { return premiumRate; }
    public Boolean getCanSubscribe() { return canSubscribe; }
    public Boolean getCanRedeem() { return canRedeem; }

    public void setNav(Double nav) { this.nav = nav; }
    public void setPremiumRate(Double premiumRate) { this.premiumRate = premiumRate; }
    public void setCanSubscribe(Boolean canSubscribe) { this.canSubscribe = canSubscribe; }
    public void setCanRedeem(Boolean canRedeem) { this.canRedeem = canRedeem; }
    public void setOpenPrice(Double openPrice) { this.openPrice = openPrice; }
    public void setClosePrice(Double closePrice) { this.closePrice = closePrice; }
    public void setHighPrice(Double highPrice) { this.highPrice = highPrice; }
    public void setLowPrice(Double lowPrice) { this.lowPrice = lowPrice; }
    public void setVolume(Long volume) { this.volume = volume; }
    public void setTurnover(Long turnover) { this.turnover = turnover; }
}
