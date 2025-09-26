package com.example.Energy_Management_System.dto;


/**
 * Data Transfer Object for power update from charger
 */
public class PowerUpdateRequest {
    
    public double consumedPower;
    
    public double vehicleMaxPower;
    
    public PowerUpdateRequest() {}
    
    public PowerUpdateRequest(double consumedPower, double vehicleMaxPower) {
        this.consumedPower = consumedPower;
        this.vehicleMaxPower = vehicleMaxPower;
    }
}
