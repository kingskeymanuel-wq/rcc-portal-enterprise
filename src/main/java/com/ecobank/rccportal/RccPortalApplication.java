package com.ecobank.rccportal;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class RccPortalApplication {

    public static void main(String[] args) {
        SpringApplication.run(RccPortalApplication.class, args);
    }
}
