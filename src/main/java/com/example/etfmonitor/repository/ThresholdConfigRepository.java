package com.example.etfmonitor.repository;

import com.example.etfmonitor.domain.InstrumentType;
import com.example.etfmonitor.entity.ThresholdConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ThresholdConfigRepository extends JpaRepository<ThresholdConfig, Long> {

    Optional<ThresholdConfig> findByInstrumentType(InstrumentType instrumentType);
}
