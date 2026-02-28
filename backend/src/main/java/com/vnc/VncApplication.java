package com.vnc;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class VncApplication {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(VncApplication.class);
        app.setHeadless(false);
        app.run(args);
    }
}
