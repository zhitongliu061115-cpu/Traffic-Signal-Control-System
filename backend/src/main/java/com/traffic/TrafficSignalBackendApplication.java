package com.traffic;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class TrafficSignalBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(TrafficSignalBackendApplication.class, args);
    }
}
