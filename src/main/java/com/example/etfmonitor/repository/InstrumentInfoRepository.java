package com.example.etfmonitor.repository;

import com.example.etfmonitor.domain.InstrumentType;
import com.example.etfmonitor.entity.InstrumentInfo;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface InstrumentInfoRepository extends JpaRepository<InstrumentInfo, String> {

    List<InstrumentInfo> findByInstrumentType(InstrumentType instrumentType);
}
