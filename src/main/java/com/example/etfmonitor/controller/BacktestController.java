package com.example.etfmonitor.controller;

import com.example.etfmonitor.model.BacktestRequest;
import com.example.etfmonitor.model.BacktestResult;
import com.example.etfmonitor.service.BacktestEngine;
import com.example.etfmonitor.service.HistoricalDataSyncService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/backtest")
public class BacktestController {

    private final HistoricalDataSyncService historicalDataSyncService;
    private final BacktestEngine backtestEngine;

    public BacktestController(HistoricalDataSyncService historicalDataSyncService,
                               BacktestEngine backtestEngine) {
        this.historicalDataSyncService = historicalDataSyncService;
        this.backtestEngine = backtestEngine;
    }

    @PostMapping("/sync-history")
    public Map<String, Object> syncHistory() {
        historicalDataSyncService.startSync();
        return Map.of("message", "历史数据同步已启动", "status", "started");
    }

    @PostMapping("/sync-history/{code}")
    public Map<String, Object> syncHistoryOne(@PathVariable String code) {
        historicalDataSyncService.startSyncOne(code);
        return Map.of("message", "单标的历史数据同步已启动: " + code, "status", "started");
    }

    @GetMapping("/sync-status")
    public Map<String, Object> syncStatus() {
        return historicalDataSyncService.getSyncStatus();
    }

    @GetMapping("/history-range")
    public Map<String, Object> historyRange() {
        return historicalDataSyncService.getHistoryRange();
    }

    @PostMapping("/run")
    public BacktestResult run(@RequestBody BacktestRequest request) {
        return backtestEngine.run(request);
    }
}
