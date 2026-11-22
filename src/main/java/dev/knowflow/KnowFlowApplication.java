package dev.knowflow;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class KnowFlowApplication {
    public static void main(String[] args) { SpringApplication.run(KnowFlowApplication.class, args); }
}
