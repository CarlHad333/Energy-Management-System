package com.example.Energy_Management_System.dto;

/**
 * Data Transfer Object for power update response
 */
public class PowerUpdateResponse {
    
    public double newAllocatedPower;
    public double totalEnergyConsumed;
    public String status;

    public long timestamp;
    
    public PowerUpdateResponse() {}
    
    // Original constructor, updated to initialize totalEnergyConsumed
    public PowerUpdateResponse(double newAllocatedPower, String status) {
        this.newAllocatedPower = newAllocatedPower;
        this.status = status;
        this.timestamp = System.currentTimeMillis();
        this.totalEnergyConsumed = 0.0; // Will be set by controller, but good to init
    }

    // NEW constructor to explicitly set totalEnergyConsumed
    public PowerUpdateResponse(double newAllocatedPower, double totalEnergyConsumed, String status) {
        this.newAllocatedPower = newAllocatedPower;
        this.totalEnergyConsumed = totalEnergyConsumed;
        this.status = status;
        this.timestamp = System.currentTimeMillis();
    }
}
