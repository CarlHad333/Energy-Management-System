package com.example.Energy_Management_System.dto;

/**
 * Data Transfer Object for starting a charging session
 */
public class StartSessionRequest {

    public String chargerId;

    public int connectorId;

    public double vehicleMaxPower;
    
    public StartSessionRequest() {}
    
    public StartSessionRequest(String chargerId, int connectorId, double vehicleMaxPower) {
        this.chargerId = chargerId;
        this.connectorId = connectorId;
        this.vehicleMaxPower = vehicleMaxPower;
    }
}
