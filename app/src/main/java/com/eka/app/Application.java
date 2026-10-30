package com.eka.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Entry point of the modular monolith. Scans every com.eka.* module. */
@SpringBootApplication(scanBasePackages = "com.eka")
public class Application {

  public static void main(String[] args) {
    SpringApplication.run(Application.class, args);
  }
}