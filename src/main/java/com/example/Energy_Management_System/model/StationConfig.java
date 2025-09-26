package com.example.Energy_Management_System.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public class StationConfig {

    @JsonProperty("stationId")
    public String stationId;

    @JsonProperty("gridCapacity")
    public double gridCapacity; // kW - Maximum grid connection capacity

    @JsonProperty("chargers")
    public List<ChargerConfig> chargers;

    @JsonProperty("battery")
    public BatteryConfig battery;
    
    public StationConfig() {}
    
    public StationConfig(String stationId, double gridCapacity, List<ChargerConfig> chargers, BatteryConfig battery) {
        this.stationId = stationId;
        this.gridCapacity = gridCapacity;
        this.chargers = chargers;
        this.battery = battery;
    }
    
    @Override
    public String toString() {
        return String.format("StationConfig{id='%s', gridCapacity=%.1fkW, chargers=%d, battery=%s}", 
                stationId, gridCapacity, chargers != null ? chargers.size() : 0, 
                battery != null ? "present" : "absent");
    }
}
