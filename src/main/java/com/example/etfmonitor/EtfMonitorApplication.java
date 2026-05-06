package com.example.etfmonitor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class EtfMonitorApplication {

    public static void main(String[] args) {
        SpringApplication.run(EtfMonitorApplication.class, args);
    }
}
