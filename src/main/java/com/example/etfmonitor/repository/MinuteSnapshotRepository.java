package com.example.etfmonitor.repository;

import com.example.etfmonitor.entity.MinuteSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface MinuteSnapshotRepository extends JpaRepository<MinuteSnapshot, Long> {

    List<MinuteSnapshot> findTop120ByCodeOrderBySnapshotMinuteDesc(String code);

    boolean existsByCodeAndSnapshotMinute(String code, LocalDateTime snapshotMinute);

    List<MinuteSnapshot> findAllBySnapshotMinute(LocalDateTime snapshotMinute);
}
