package com.wasteoil;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class WasteOilApplication {

    public static void main(String[] args) {
        SpringApplication.run(WasteOilApplication.class, args);
    }
}
