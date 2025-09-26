package com.example.Energy_Management_System.dto;

/**
 * Data Transfer Object for session start response
 */
public class StartSessionResponse {
    
    public String sessionId;

    public double allocatedPower;
    public double totalEnergyConsumed; // CORRECTED: ADDED THIS FIELD
    public String status;
    
    public StartSessionResponse() {}
    
    // Original constructor, updated to initialize totalEnergyConsumed
    public StartSessionResponse(String sessionId, double allocatedPower, String status) {
        this.sessionId = sessionId;
        this.allocatedPower = allocatedPower;
        this.status = status;
        this.totalEnergyConsumed = 0.0; // Initialize for a new session
    }

    // NEW constructor to explicitly set totalEnergyConsumed
    public StartSessionResponse(String sessionId, double allocatedPower, double totalEnergyConsumed, String status) {
        this.sessionId = sessionId;
        this.allocatedPower = allocatedPower;
        this.totalEnergyConsumed = totalEnergyConsumed;
        this.status = status;
    }
}
