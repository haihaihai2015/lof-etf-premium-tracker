package com.example.etfmonitor.entity;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.Objects;

public class DailyBarId implements Serializable {

    private String code;
    private LocalDate tradeDate;

    public DailyBarId() {
    }

    public DailyBarId(String code, LocalDate tradeDate) {
        this.code = code;
        this.tradeDate = tradeDate;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DailyBarId that = (DailyBarId) o;
        return Objects.equals(code, that.code) && Objects.equals(tradeDate, that.tradeDate);
    }

    @Override
    public int hashCode() {
        return Objects.hash(code, tradeDate);
    }
}
