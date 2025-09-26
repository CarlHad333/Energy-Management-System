package com.example.Energy_Management_System.config;

import com.example.Energy_Management_System.model.BatteryConfig;
import com.example.Energy_Management_System.model.ChargerConfig;
import com.example.Energy_Management_System.model.StationConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.List;

/**
 * Configuration class for Energy Management System
 * 
 * Provides default beans for station configuration, chargers, and battery system.
 * In a production environment, these would typically be loaded from external configuration.
 */
@Configuration
public class StationConfiguration {

    /**
     * Default battery configuration
     * 
     * @return BatteryConfig bean with default values
     */
    @Bean
    public BatteryConfig batteryConfig() {
        return new BatteryConfig(100.0, 50.0); // 100kWh capacity, 50kW max power
    }

    /**
     * Default station configuration
     * 
     * @return StationConfig bean with default values
     */
    @Bean
    public StationConfig stationConfig() {
        // Create charger configurations
        List<ChargerConfig> chargers = Arrays.asList(
            new ChargerConfig("CP001", 350.0, 2), // 150kW charger with 2 connectors
            new ChargerConfig("CP002", 350.0, 2), // 150kW charger with 2 connectors
            new ChargerConfig("CP003", 150.0, 1)   // 50kW charger with 1 connector
        );
        
        // Create station configuration
        return new StationConfig(
            "STATION_001",           // Station ID
            500.0,                   // 500kW grid capacity
            chargers,                // List of chargers
            batteryConfig()          // Battery system (inject the bean)
        );
    }
}
