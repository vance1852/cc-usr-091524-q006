package com.admin.equipment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class EquipmentMaintenanceApplication {
    public static void main(String[] args) {
        SpringApplication.run(EquipmentMaintenanceApplication.class, args);
    }
}
