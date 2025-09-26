package com.example.Energy_Management_System.dto;

import com.example.Energy_Management_System.model.Session;

import java.util.List;
import java.util.Map;

/**
 * Data Transfer Object for station status response
 */
public class StationStatusResponse {
    
    public String stationId;
    public double gridCapacity;
    public double totalAllocatedPower;
    public double totalConsumedPower;
    public List<SessionInfo> activeSessions;
    public BatteryStatus batteryStatus;
    public Map<String, Double> powerAllocation;
    public long timestamp;
    
    public StationStatusResponse() {}
    
    public static class SessionInfo {
        public String sessionId;
        public String chargerId;
        public int connectorId;
        public double vehicleMaxPower;
        public double allocatedPower;
        public double consumedPower;
        public String state;
        
        public SessionInfo(Session session) {
            this.sessionId = session.getSessionId();
            this.chargerId = session.getChargerId();
            this.connectorId = session.getConnectorId();
            this.vehicleMaxPower = session.getVehicleMaxPower();
            this.allocatedPower = session.getAllocatedPower();
            this.consumedPower = session.getConsumedPower();
            this.state = session.getState().toString();
        }
    }
    
    public static class BatteryStatus {
        public double soc; // kWh
        public double socPercentage;
        public double maxDischarge; // kW
        public double maxCharge; // kW
        
        public BatteryStatus(double soc, double capacity, double maxPower) {
            this.soc = soc; // kWh Amount charged
            this.socPercentage = (soc / capacity) * 100.0; // Percentage charged
            this.maxDischarge = maxPower; // Can discharge that amount
            this.maxCharge = maxPower; // Can charge that amount
        }
    }
}
