package rw.teleclinic.core;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/** TeleClinic core: one deployable, modules enforced by Spring Modulith (ADR-001). */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class TeleClinicApplication {

    public static void main(String[] args) {
        SpringApplication.run(TeleClinicApplication.class, args);
    }
}
