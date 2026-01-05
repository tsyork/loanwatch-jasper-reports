package com.jasperdev.reports;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class JasperReportsApplication {

    public static void main(String[] args) {
        SpringApplication.run(JasperReportsApplication.class, args);
    }
}
