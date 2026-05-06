package com.example.etfmonitor.controller;

import com.example.etfmonitor.model.FilterMetaResponse;
import com.example.etfmonitor.service.InstrumentQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/meta")
public class MetaController {

    private final InstrumentQueryService instrumentQueryService;

    public MetaController(InstrumentQueryService instrumentQueryService) {
        this.instrumentQueryService = instrumentQueryService;
    }

    @GetMapping("/filters")
    public FilterMetaResponse filters() {
        return instrumentQueryService.filterMeta();
    }
}
