package com.kryptokrauts;

import com.kryptokrauts.config.NameConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(NameConfig.class)
public class App {
  public static void main(String[] args) {
    SpringApplication.run(App.class, args);
  }
}
