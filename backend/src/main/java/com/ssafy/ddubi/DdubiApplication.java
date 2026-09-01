package com.ssafy.ddubi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class DdubiApplication {

    public static void main(String[] args) {
        SpringApplication.run(DdubiApplication.class, args);
    }
}
