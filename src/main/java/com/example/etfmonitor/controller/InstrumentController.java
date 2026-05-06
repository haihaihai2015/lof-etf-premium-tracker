package com.example.etfmonitor.controller;

import com.example.etfmonitor.domain.InstrumentType;
import com.example.etfmonitor.model.InstrumentDetailResponse;
import com.example.etfmonitor.model.InstrumentListResponse;
import com.example.etfmonitor.model.RefreshResponse;
import com.example.etfmonitor.model.TimelinePoint;
import com.example.etfmonitor.service.InstrumentQueryService;
import com.example.etfmonitor.service.InstrumentUniverseSyncService;
import com.example.etfmonitor.service.MarketDataRefreshService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@RestController
@RequestMapping("/api/instruments")
public class InstrumentController {

    private final InstrumentQueryService instrumentQueryService;
    private final MarketDataRefreshService marketDataRefreshService;
    private final InstrumentUniverseSyncService instrumentUniverseSyncService;
    private final ExecutorService asyncExecutor = Executors.newSingleThreadExecutor(
        r -> { Thread t = new Thread(r, "refresh-async"); t.setDaemon(true); return t; });

    public InstrumentController(InstrumentQueryService instrumentQueryService,
                                MarketDataRefreshService marketDataRefreshService,
                                InstrumentUniverseSyncService instrumentUniverseSyncService) {
        this.instrumentQueryService = instrumentQueryService;
        this.marketDataRefreshService = marketDataRefreshService;
        this.instrumentUniverseSyncService = instrumentUniverseSyncService;
    }

    @GetMapping
    public InstrumentListResponse list(@RequestParam(required = false) InstrumentType type,
                                       @RequestParam(required = false) String sortBy,
                                       @RequestParam(defaultValue = "desc") String sortOrder,
                                       @RequestParam(required = false) String keyword,
                                       @RequestParam(required = false) Double premiumMin,
                                       @RequestParam(required = false) Long turnoverMin,
                                       @RequestParam(required = false) Double spreadMax,
                                       @RequestParam(defaultValue = "false") boolean subscribableOnly,
                                       @RequestParam(defaultValue = "false") boolean purchasableOnly,
                                       @RequestParam(defaultValue = "false") boolean highLiquidityOnly,
                                       @RequestParam(required = false) String codes,
                                       @RequestParam(defaultValue = "1") int page,
                                       @RequestParam(defaultValue = "100") int pageSize) {
        java.util.Set<String> codeSet = null;
        if (codes != null && !codes.isBlank()) {
            codeSet = java.util.Set.of(codes.split(","));
        }
        return instrumentQueryService.listInstruments(type, sortBy, sortOrder, keyword, premiumMin, turnoverMin, spreadMax,
            subscribableOnly, purchasableOnly, highLiquidityOnly, codeSet, page, pageSize);
    }

    @GetMapping("/stats")
    public java.util.Map<String, Object> stats(@RequestParam(required = false) InstrumentType type) {
        return instrumentQueryService.computeStats(type);
    }

    @GetMapping("/{code}")
    public InstrumentDetailResponse detail(@PathVariable String code) {
        return instrumentQueryService.getDetail(code);
    }

    @GetMapping("/{code}/timeline")
    public List<TimelinePoint> timeline(@PathVariable String code) {
        return instrumentQueryService.timeline(code);
    }

    @GetMapping("/sparklines")
    public java.util.Map<String, List<Double>> sparklines(@RequestParam String codes) {
        java.util.Set<String> codeSet = java.util.Set.of(codes.split(","));
        return instrumentQueryService.sparklines(codeSet);
    }

    @PostMapping("/refresh")
    public RefreshResponse refresh(@RequestParam(defaultValue = "realtime") String scope) {
        String normalized = scope.toLowerCase();
        return switch (normalized) {
            case "universe", "all" -> {
                asyncExecutor.submit(() -> {
                    instrumentUniverseSyncService.syncUniverse();
                    marketDataRefreshService.refreshRealtimeData();
                });
                yield new RefreshResponse(normalized, 0, "已触发全量同步，数据将在稍后更新");
            }
            case "realtime" -> {
                asyncExecutor.submit(() -> marketDataRefreshService.refreshRealtimeData());
                yield new RefreshResponse(normalized, 0, "已触发实时行情刷新，数据将在稍后更新");
            }
            default -> new RefreshResponse(normalized, 0, "未知刷新范围，请使用 realtime、universe 或 all");
        };
    }
}
