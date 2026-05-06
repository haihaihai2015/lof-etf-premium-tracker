package com.example.etfmonitor.controller;

import com.example.etfmonitor.EtfMonitorApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = EtfMonitorApplication.class)
@AutoConfigureMockMvc
class InstrumentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void shouldReturnEtfRows() throws Exception {
        mockMvc.perform(get("/api/instruments").param("type", "ETF").param("keyword", "510300"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].instrumentType").value("ETF"))
            .andExpect(jsonPath("$.items[0].code").value("510300"))
            .andExpect(jsonPath("$.total").isNumber());
    }

    @Test
    void detailShouldContainTimeline() throws Exception {
        mockMvc.perform(get("/api/instruments/510300"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.instrument.code").value("510300"))
            .andExpect(jsonPath("$.timeline").isArray());
    }

    @Test
    void refreshShouldRespond() throws Exception {
        mockMvc.perform(post("/api/instruments/refresh").param("scope", "realtime"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.scope").value("realtime"));
    }
}
