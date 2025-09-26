package com.example.Energy_Management_System.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public class ChargerConfig {

    @JsonProperty("id")
    public String id;

    @JsonProperty("maxPower")
    public double maxPower; // kW - Maximum power shared across all connectors

    @JsonProperty("connectors")
    public int connectors; // Number of connectors per station
    
    public ChargerConfig() {}
    
    public ChargerConfig(String id, double maxPower, int connectors) {
        this.id = id;
        this.maxPower = maxPower;
        this.connectors = connectors;
    }
    
    @Override
    public String toString() {
        return String.format("Charger{id='%s', maxPower=%.1fkW, connectors=%d}", 
                id, maxPower, connectors);
    }
}
