package com.schedula.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.schedula")
public class SchedulaApplication {

    public static void main(String[] args) {
        SpringApplication.run(SchedulaApplication.class, args);
    }
}
