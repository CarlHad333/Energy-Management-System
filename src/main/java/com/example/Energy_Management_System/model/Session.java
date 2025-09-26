package com.example.Energy_Management_System.model;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

public class Session {
    
    public enum State {
        STARTING,
        ACTIVE,
        STOPPING,
        COMPLETED
    }
    
    private final String sessionId;
    private final String chargerId;
    private final int connectorId;
    private final LocalDateTime startTime;

    private final AtomicReference<Double> vehicleMaxPower = new AtomicReference<>(0.0);
    private final AtomicReference<Double> allocatedPower = new AtomicReference<>(0.0);
    private final AtomicReference<Double> consumedPower = new AtomicReference<>(0.0);
    private final AtomicReference<Double> totalEnergyConsumed = new AtomicReference<>(0.0); // New field for total energy in kWh
    private final AtomicReference<State> state = new AtomicReference<>(State.STARTING);
    
    private volatile LocalDateTime lastUpdateTime;
    
    public Session(String sessionId, String chargerId, int connectorId, double vehicleMaxPower) {
        this.sessionId = Objects.requireNonNull(sessionId, "Session ID cannot be null");
        this.chargerId = Objects.requireNonNull(chargerId, "Charger ID cannot be null");
        this.connectorId = connectorId;
        this.startTime = LocalDateTime.now();
        this.lastUpdateTime = this.startTime;
        this.vehicleMaxPower.set(vehicleMaxPower);
        this.state.set(State.ACTIVE);
        this.totalEnergyConsumed.set(0.0); // Initialize to 0
    }

    public String getSessionId() { return sessionId; }
    public String getChargerId() { return chargerId; }
    public int getConnectorId() { return connectorId; }
    public LocalDateTime getStartTime() { return startTime; }
    public LocalDateTime getLastUpdateTime() { return lastUpdateTime; }
    
    public double getVehicleMaxPower() { return vehicleMaxPower.get(); }
    public double getAllocatedPower() { return allocatedPower.get(); }
    public double getConsumedPower() { return consumedPower.get(); }
    public double getTotalEnergyConsumed() { return totalEnergyConsumed.get(); } // New getter
    public State getState() { return state.get(); }

    public void updateVehicleMaxPower(double power) {
        this.vehicleMaxPower.set(Math.max(0, power));
        this.lastUpdateTime = LocalDateTime.now();
    }
    
    public void setAllocatedPower(double power) {
        this.allocatedPower.set(Math.max(0, power));
        this.lastUpdateTime = LocalDateTime.now();
    }
    
    public void updateConsumedPower(double power) {
        this.consumedPower.set(Math.max(0, power));
        this.lastUpdateTime = LocalDateTime.now();
    }

    public void addEnergyConsumed(double energyKWh) { // New method to add consumed energy
        this.totalEnergyConsumed.updateAndGet(current -> current + Math.max(0, energyKWh));
        this.lastUpdateTime = LocalDateTime.now();
    }
    
    public void setState(State newState) {
        this.state.set(newState);
        this.lastUpdateTime = LocalDateTime.now();
    }

    public String getConnectorKey() {
        return chargerId + "_" + connectorId;
    }

    public double getEffectiveDemand() {
        return Math.min(vehicleMaxPower.get(), getAllocatedPower());
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Session session = (Session) o;
        return sessionId.equals(session.sessionId);
    }
    
    @Override
    public String toString() {
        return String.format("Session{id='%s', charger='%s', connector=%d, vehicleMax=%.1fkW, allocated=%.1fkW, consumed=%.1fkW, totalEnergy=%.1fkWh, state=%s}", 
                sessionId, chargerId, connectorId, vehicleMaxPower.get(), 
                allocatedPower.get(), consumedPower.get(), totalEnergyConsumed.get(), state.get());
    }
}
