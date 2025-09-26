package com.example.Energy_Management_System.service;

import com.example.Energy_Management_System.model.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration; // NEW IMPORT
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Thread-safe Session Manager
 * Handles concurrent session lifecycle and connector mapping
 * 
 * Design decisions:
 * - ConcurrentHashMap for thread-safe operations without synchronized blocks
 * - Atomic operations in Session model for consistent state updates
 * - Connector availability checking to prevent double-booking
 * 
 * Based on OCPP protocol patterns and EV charging session management best practices
 */
@Service
public class SessionManager {
    
    private static final Logger logger = LoggerFactory.getLogger(SessionManager.class);
    
    // Primary session storage - thread-safe
    private final Map<String, Session> activeSessions = new ConcurrentHashMap<>();
    
    // Secondary index for fast connector lookup - thread-safe
    private final Map<String, String> connectorToSession = new ConcurrentHashMap<>();
    
    /**
     * Start a new charging session
     * Thread-safe operation with connector availability check
     * 
     * @param chargerId Charger point identifier
     * @param connectorId Connector number (1-based)
     * @param vehicleMaxPower Vehicle's maximum power acceptance (kW)
     * @return New session or null if connector occupied
     */
    public Session startSession(String chargerId, int connectorId, double vehicleMaxPower) {
        String connectorKey = getConnectorKey(chargerId, connectorId);
        
        // Check connector availability (atomic operation)
        if (connectorToSession.containsKey(connectorKey)) {
            logger.warn("Connector {} is already occupied", connectorKey);
            return null;
        }
        
        // Generate unique session ID
        String sessionId = generateSessionId();
        Session session = new Session(sessionId, chargerId, connectorId, vehicleMaxPower);
        
        // Atomic registration (either both succeed or both fail)
        Session existing = activeSessions.putIfAbsent(sessionId, session);
        if (existing != null) {
            logger.error("Session ID collision: {}", sessionId);
            return null;
        }
        
        String existingConnector = connectorToSession.putIfAbsent(connectorKey, sessionId);
        if (existingConnector != null) {
            // Rollback session registration
            activeSessions.remove(sessionId);
            logger.warn("Connector {} became occupied during session start", connectorKey);
            return null;
        }
        
        logger.info("Started session: {}", session);
        return session;
    }
    
    /**
     * Stop and remove a charging session
     * Thread-safe cleanup of all references
     * 
     * @param sessionId Session identifier to stop
     * @return true if session was found and stopped
     */
    public boolean stopSession(String sessionId) {
        Session session = activeSessions.remove(sessionId);
        if (session == null) {
            logger.warn("Attempted to stop non-existent session: {}", sessionId);
            return false;
        }
        
        session.setState(Session.State.STOPPING);
        
        // Clean up connector mapping
        String connectorKey = session.getConnectorKey();
        connectorToSession.remove(connectorKey);
        
        logger.info("Stopped session: {}", session);
        return true;
    }
    
    /**
     * Update session power consumption and vehicle capability
     * Thread-safe atomic updates
     * 
     * @param sessionId Session to update
     * @param consumedPower Current power consumption (kW)
     * @param vehicleMaxPower Updated vehicle max power capability (kW)
     * @return true if session was found and updated
     */
    public boolean updateSessionPower(String sessionId, double consumedPower, double vehicleMaxPower) {
        Session session = activeSessions.get(sessionId);
        if (session == null) {
            logger.warn("Attempted to update non-existent session: {}", sessionId);
            return false;
        }
        
        // Calculate energy consumed since last update
        LocalDateTime previousUpdateTime = session.getLastUpdateTime();
        LocalDateTime now = LocalDateTime.now();
        Duration duration = Duration.between(previousUpdateTime, now);
        double hoursElapsed = duration.toMillis() / (1000.0 * 3600.0); // Convert milliseconds to hours

        // Add to total energy consumed
        session.addEnergyConsumed(consumedPower * hoursElapsed);

        // Update current instantaneous consumed power and vehicle max power
        session.updateConsumedPower(consumedPower);
        session.updateVehicleMaxPower(vehicleMaxPower);
        
        logger.debug("Updated session {}: consumed={}kW, vehicleMax={}kW, totalEnergy={:.2f}kWh", 
                sessionId, consumedPower, vehicleMaxPower, session.getTotalEnergyConsumed());
        return true;
    }
    
    /**
     * Get all active sessions (thread-safe snapshot)
     */
    public Collection<Session> getAllSessions() {
        return new ArrayList<>(activeSessions.values());
    }
    
    /**
     * Get session by ID
     */
    public Session getSession(String sessionId) {
        return activeSessions.get(sessionId);
    }
    
    /**
     * Get sessions for a specific charger
     */
    public List<Session> getSessionsByCharger(String chargerId) {
        return activeSessions.values().stream()
                .filter(session -> chargerId.equals(session.getChargerId()))
                .collect(Collectors.toList());
    }
    
    /**
     * Check if connector is available
     */
    public boolean isConnectorAvailable(String chargerId, int connectorId) {
        String connectorKey = getConnectorKey(chargerId, connectorId);
        return !connectorToSession.containsKey(connectorKey);
    }
    
    /**
     * Get total number of active sessions
     */
    public int getActiveSessionCount() {
        return activeSessions.size();
    }
    
    /**
     * Get total allocated power across all sessions
     */
    public double getTotalAllocatedPower() {
        return activeSessions.values().stream()
                .mapToDouble(Session::getAllocatedPower)
                .sum();
    }
    
    /**
     * Get total consumed power across all sessions
     */
    public double getTotalConsumedPower() {
        return activeSessions.values().stream()
                .mapToDouble(Session::getConsumedPower)
                .sum();
    }
    
    /**
     * Get total consumed energy across all sessions
     */
    public double getTotalEnergyConsumed() { // NEW METHOD
        return activeSessions.values().stream()
                .mapToDouble(Session::getTotalEnergyConsumed)
                .sum();
    }
    
    /**
     * Get sessions grouped by charger for load management
     */
    public Map<String, List<Session>> getSessionsByChargerMap() {
        return activeSessions.values().stream()
                .collect(Collectors.groupingBy(Session::getChargerId));
    }
    
    // Private utility methods
    
    private String getConnectorKey(String chargerId, int connectorId) {
        return chargerId + "_" + connectorId;
    }
    
    private String generateSessionId() {
        return "session_" + UUID.randomUUID().toString();
    }
    
    /**
     * Cleanup stale sessions (for maintenance/monitoring)
     * In production, this could be scheduled periodically
     */
    public int cleanupStaleSessions(long maxIdleMinutes) {
        // Implementation would check lastUpdateTime and remove stale sessions
        // For now, we'll keep it simple
        return 0;
    }
}
