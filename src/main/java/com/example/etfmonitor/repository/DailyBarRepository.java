package com.example.etfmonitor.repository;

import com.example.etfmonitor.entity.DailyBar;
import com.example.etfmonitor.entity.DailyBarId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface DailyBarRepository extends JpaRepository<DailyBar, DailyBarId> {

    List<DailyBar> findByCodeAndTradeDateBetweenOrderByTradeDateAsc(String code, LocalDate start, LocalDate end);

    List<DailyBar> findByCodeOrderByTradeDateAsc(String code);

    @Query("SELECT MAX(d.tradeDate) FROM DailyBar d WHERE d.code = :code")
    Optional<LocalDate> findMaxTradeDateByCode(@Param("code") String code);

    @Query("SELECT MIN(d.tradeDate) FROM DailyBar d")
    Optional<LocalDate> findMinTradeDate();

    @Query("SELECT MAX(d.tradeDate) FROM DailyBar d")
    Optional<LocalDate> findMaxTradeDate();

    @Query("SELECT DISTINCT d.tradeDate FROM DailyBar d WHERE d.tradeDate BETWEEN :start AND :end ORDER BY d.tradeDate ASC")
    List<LocalDate> findDistinctTradeDates(@Param("start") LocalDate start, @Param("end") LocalDate end);

    long countByCode(String code);

    List<DailyBar> findByCodeAndPremiumRateIsNullAndNavIsNotNull(String code);

    @Query("SELECT MIN(d.tradeDate) FROM DailyBar d WHERE d.premiumRate IS NOT NULL")
    Optional<LocalDate> findMinPremiumDate();

    @Query("SELECT MAX(d.tradeDate) FROM DailyBar d WHERE d.premiumRate IS NOT NULL")
    Optional<LocalDate> findMaxPremiumDate();

    @Query("SELECT COUNT(DISTINCT d.code) FROM DailyBar d WHERE d.premiumRate IS NOT NULL")
    long countDistinctCodesWithPremium();
}
