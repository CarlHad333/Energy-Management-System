package com.example.Energy_Management_System.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Battery Energy Storage System (BESS) Configuration DTO
 * Represents stationary battery for peak shaving and grid support
 * Based on IEEE standards for energy storage systems
 */
public class BatteryConfig {

    @JsonProperty("initialCapacity")
    public double initialCapacity; // kWh - Total energy storage capacity

    @JsonProperty("power")
    public double power; // kW - Maximum charge/discharge power rating
    
    public BatteryConfig() {}
    
    public BatteryConfig(double initialCapacity, double power) {
        this.initialCapacity = initialCapacity;
        this.power = power;
    }
    
    @Override
    public String toString() {
        return String.format("Battery{capacity=%.1fkWh, power=%.1fkW}", 
                initialCapacity, power);
    }
}
