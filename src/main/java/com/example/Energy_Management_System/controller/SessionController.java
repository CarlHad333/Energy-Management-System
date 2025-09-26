package com.example.Energy_Management_System.controller;

import com.example.Energy_Management_System.dto.*;
import com.example.Energy_Management_System.model.Session;
import com.example.Energy_Management_System.model.StationConfig;
import com.example.Energy_Management_System.service.LoadManager;
import com.example.Energy_Management_System.service.SessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.util.Map;

/**
 * REST API Controller for Charging Session Management
 * 
 * Implements synchronous endpoints using Spring Web MVC for:
 * - Fast response times with simple synchronous processing
 * - Thread-safe concurrent request handling
 * - Easy integration with existing Spring Boot applications
 * 
 * API design follows RESTful principles and OCPP-inspired patterns
 * for integration with EV charging infrastructure
 */
@RestController
@RequestMapping("/api/v1/sessions")
@CrossOrigin(origins = "*") // Allow CORS for web interface
public class SessionController {

    private static final Logger logger = LoggerFactory.getLogger(SessionController.class);

    private final SessionManager sessionManager;
    private final LoadManager loadManager;
    private final StationConfig stationConfig;
    
    public SessionController(SessionManager sessionManager, LoadManager loadManager, StationConfig stationConfig) {
        this.sessionManager = sessionManager;
        this.loadManager = loadManager;
        this.stationConfig = stationConfig;
    }
    
    /**
     * Start a new charging session
     * 
     * POST /api/v1/sessions
     * Body: {"chargerId": "CP001", "connectorId": 1, "vehicleMaxPower": 150}
     * 
     * Based on OCPP StartTransaction message pattern
     * Triggers immediate power allocation recomputation
     */
    @PostMapping
    public ResponseEntity<StartSessionResponse> startSession(
            @Valid @RequestBody StartSessionRequest request) {
        
        logger.info("Starting session request: charger={}, connector={}, vehicleMaxPower={}kW", 
                request.chargerId, request.connectorId, request.vehicleMaxPower);
        
        try {
            // Validate charger exists and connector ID is valid
            if (!isValidChargerAndConnector(request.chargerId, request.connectorId)) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(new StartSessionResponse(null, 0.0, "INVALID_CHARGER_OR_CONNECTOR"));
            }
            
            // Check connector availability (thread-safe)
            if (!sessionManager.isConnectorAvailable(request.chargerId, request.connectorId)) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(new StartSessionResponse(null, 0.0, "CONNECTOR_OCCUPIED"));
            }
            
            // Start session (atomic operation)
            Session session = sessionManager.startSession(
                    request.chargerId, 
                    request.connectorId, 
                    request.vehicleMaxPower);
            
            if (session == null) {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(new StartSessionResponse(null, 0.0, "SESSION_START_FAILED"));
            }
            
            // Trigger load management recomputation (synchronous)
            loadManager.recomputeAllocations();
            
            // Update session with final allocated power
            Session updatedSession = sessionManager.getSession(session.getSessionId());
            double finalAllocatedPower = updatedSession != null ? updatedSession.getAllocatedPower() : session.getAllocatedPower();
            double totalEnergy = updatedSession != null ? updatedSession.getTotalEnergyConsumed() : 0.0; // Get total energy
            
            logger.info("Session started successfully: {}", session.getSessionId());
            
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(new StartSessionResponse(
                            session.getSessionId(), 
                            finalAllocatedPower, 
                            totalEnergy, // Include total energy
                            "SESSION_STARTED"));
        } catch (Exception error) {
            logger.error("Error starting session", error);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new StartSessionResponse(null, 0.0, "INTERNAL_ERROR"));
        }
    }
    
    /**
     * Update session power information
     * 
     * POST /api/v1/sessions/{sessionId}/power-update
     * Body: {"consumedPower": 50, "vehicleMaxPower": 100}
     * 
     * Called by charger to report actual power consumption and vehicle capability changes
     * Triggers load reallocation to optimize distribution
     */
    @PostMapping("/{sessionId}/power-update")
    public ResponseEntity<PowerUpdateResponse> updatePower(
            @PathVariable String sessionId,
            @Valid @RequestBody PowerUpdateRequest request) {
        
        logger.debug("Power update for session {}: consumed={}kW, vehicleMax={}kW", 
                sessionId, request.consumedPower, request.vehicleMaxPower);
        
        try {
            // Validate session exists
            Session session = sessionManager.getSession(sessionId);
            if (session == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(new PowerUpdateResponse(0.0, "SESSION_NOT_FOUND"));
            }
            
            // Validate consumedPower against vehicleMaxPower and allocatedPower
            if (request.consumedPower > request.vehicleMaxPower) {
                logger.warn("Invalid power update for session {}: consumedPower ({}) exceeds vehicleMaxPower ({})",
                        sessionId, request.consumedPower, request.vehicleMaxPower );
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(new PowerUpdateResponse(session.getAllocatedPower(), "INVALID_CONSUMED_POWER"));
            }
            
            // Update power information (thread-safe atomic updates)
            boolean updated = sessionManager.updateSessionPower(
                    sessionId, 
                    request.consumedPower, 
                    request.vehicleMaxPower);
            
            if (!updated) {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(new PowerUpdateResponse(0.0, "UPDATE_FAILED"));
            }
            
            // Trigger load management recomputation (synchronous)
            loadManager.recomputeAllocations();
            
            // Get updated session with new allocated power
            Session updatedSession = sessionManager.getSession(sessionId);
            double newAllocatedPower = updatedSession != null ? updatedSession.getAllocatedPower() : session.getAllocatedPower();
            double totalEnergy = updatedSession != null ? updatedSession.getTotalEnergyConsumed() : 0.0; // Get total energy
            
            logger.debug("Power updated for session {}: new allocation={}kW", 
                    sessionId, newAllocatedPower);
            
            return ResponseEntity.ok(new PowerUpdateResponse(
                    newAllocatedPower, 
                    totalEnergy, // Include total energy
                    "POWER_UPDATED"));
        } catch (Exception error) {
            logger.error("Error updating power for session " + sessionId, error);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new PowerUpdateResponse(0.0, "INTERNAL_ERROR"));
        }
    }
    
    /**
     * Stop a charging session
     * 
     * POST /api/v1/sessions/{sessionId}/stop
     * Body: {"consumedEnergy": 45.5, "reason": "USER_STOPPED"}
     * 
     * Based on OCPP StopTransaction message pattern
     * Triggers power reallocation for remaining sessions
     */
    @PostMapping("/{sessionId}/stop")
    public ResponseEntity<Map<String, Object>> stopSession(
            @PathVariable String sessionId,
            @RequestBody(required = false) Map<String, Object> stopData) {
        
        logger.info("Stopping session: {}", sessionId);
        
        try {
            // Validate session exists
            Session session = sessionManager.getSession(sessionId);
            if (session == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.<String, Object>of("success", false, "error", "SESSION_NOT_FOUND"));
            }
            
            // Stop session (atomic cleanup)
            boolean stopped = sessionManager.stopSession(sessionId);
            
            if (!stopped) {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(Map.<String, Object>of("success", false, "error", "STOP_FAILED"));
            }
            
            // Trigger load management recomputation for remaining sessions (synchronous)
            loadManager.recomputeAllocations();
            
            // Prepare response with session summary
            Map<String, Object> response = Map.<String, Object>of(
                    "success", true,
                    "sessionId", sessionId,
                    "chargerId", session.getChargerId(),
                    "connectorId", session.getConnectorId(),
                    "finalAllocatedPower", session.getAllocatedPower(),
                    "lastConsumedPower", session.getConsumedPower(),
                    "stopTime", System.currentTimeMillis()
            );
            
            logger.info("Session stopped successfully: {}", sessionId);
            return ResponseEntity.ok(response);
        } catch (Exception error) {
            logger.error("Error stopping session " + sessionId, error);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.<String, Object>of("success", false, "error", "INTERNAL_ERROR"));
        }
    }
    
    /**
     * Get session details
     * 
     * GET /api/v1/sessions/{sessionId}
     * 
     * Returns current session state and power allocation
     */
    @GetMapping("/{sessionId}")
    public ResponseEntity<Map<String, Object>> getSession(@PathVariable String sessionId) {
        
        try {
            Session session = sessionManager.getSession(sessionId);
            if (session == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.<String, Object>of("error", "SESSION_NOT_FOUND"));
            }
            
            Map<String, Object> sessionData = Map.<String, Object>of(
                    "sessionId", session.getSessionId(),
                    "chargerId", session.getChargerId(),
                    "connectorId", session.getConnectorId(),
                    "vehicleMaxPower", session.getVehicleMaxPower(),
                    "allocatedPower", session.getAllocatedPower(),
                    "consumedPower", session.getConsumedPower(),
                    "state", session.getState().toString(),
                    "startTime", session.getStartTime().toString(),
                    "lastUpdate", session.getLastUpdateTime().toString(),
                    "totalEnergyConsumed", session.getTotalEnergyConsumed() // Add total energy
            );
            
            return ResponseEntity.ok(sessionData);
        } catch (Exception error) {
            logger.error("Error retrieving session " + sessionId, error);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.<String, Object>of("error", "INTERNAL_ERROR"));
        }
    }
    
    /**
     * List all active sessions
     * 
     * GET /api/v1/sessions
     * 
     * Returns array of all active sessions with current allocations
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> listSessions() {
        
        try {
            var sessions = sessionManager.getAllSessions();
            
            var sessionList = sessions.stream()
                    .map(session -> Map.<String, Object>of(
                            "sessionId", session.getSessionId(),
                            "chargerId", session.getChargerId(),
                            "connectorId", session.getConnectorId(),
                            "vehicleMaxPower", session.getVehicleMaxPower(),
                            "allocatedPower", session.getAllocatedPower(),
                            "consumedPower", session.getConsumedPower(),
                            "state", session.getState().toString(),
                            "totalEnergyConsumed", session.getTotalEnergyConsumed() // Add total energy
                    ))
                    .toList();
            
            Map<String, Object> response = Map.<String, Object>of(
                    "sessions", sessionList,
                    "totalSessions", sessionList.size(),
                    "totalAllocatedPower", sessionManager.getTotalAllocatedPower(),
                    "totalConsumedPower", sessionManager.getTotalConsumedPower(),
                    "totalConsumedEnergy", sessionManager.getTotalEnergyConsumed(), // Add total consumed energy
                    "timestamp", System.currentTimeMillis()
            );
            
            return ResponseEntity.ok(response);
        } catch (Exception error) {
            logger.error("Error listing sessions", error);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.<String, Object>of("error", "INTERNAL_ERROR"));
        }
    }
    
    /**
     * Validate that charger exists and connector ID is within valid range
     */
    private boolean isValidChargerAndConnector(String chargerId, int connectorId) {
        if (stationConfig.chargers == null) {
            return false;
        }
        
        for (var charger : stationConfig.chargers) {
            if (charger.id.equals(chargerId)) {
                // Connector ID must be between 1 and maxConnectors (inclusive)
                return connectorId >= 1 && connectorId <= charger.connectors;
            }
        }
        
        return false; // Charger not found
    }
}
