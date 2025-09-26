package com.example.Energy_Management_System.controller;

import com.example.Energy_Management_System.dto.StationStatusResponse;
import com.example.Energy_Management_System.model.StationConfig;
import com.example.Energy_Management_System.service.BessController;
import com.example.Energy_Management_System.service.LoadManager;
import com.example.Energy_Management_System.service.SessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * REST API Controller for Station-wide Operations and Status
 * 
 * Provides synchronous endpoints for:
 * - Real-time station status and power allocation
 * - Load management summaries and metrics
 * - BESS status and battery information
 * - Configuration and capability queries
 * 
 * Designed for monitoring dashboards, external systems integration,
 * and operational visibility into the energy management system
 */
@RestController
@RequestMapping("/api/v1/station")
@CrossOrigin(origins = "*")
public class StationController {
    
    private static final Logger logger = LoggerFactory.getLogger(StationController.class);
    
    private final StationConfig stationConfig;
    private final SessionManager sessionManager;
    private final LoadManager loadManager;
    private final BessController bessController;
    
    public StationController(StationConfig stationConfig,
                           SessionManager sessionManager,
                           LoadManager loadManager,
                           BessController bessController) {
        this.stationConfig = stationConfig;
        this.sessionManager = sessionManager;
        this.loadManager = loadManager;
        this.bessController = bessController;
    }
    
    /**
     * Get comprehensive station status
     * 
     * GET /api/v1/station/status
     * 
     * Returns complete station state including:
     * - Active charging sessions and power allocations
     * - Grid capacity utilization
     * - BESS state of charge and power flow
     * - Load management summary
     */
    @GetMapping("/status")
    public ResponseEntity<StationStatusResponse> getStationStatus() {
        
        try {
            StationStatusResponse response = new StationStatusResponse();
            
            // Station identification and configuration
            response.stationId = stationConfig.stationId;
            response.gridCapacity = stationConfig.gridCapacity;
            response.timestamp = System.currentTimeMillis();
            
            // Session information
            var sessions = sessionManager.getAllSessions();
            response.activeSessions = sessions.stream()
                    .map(StationStatusResponse.SessionInfo::new)
                    .collect(Collectors.toList());
            
            // Power totals
            response.totalAllocatedPower = sessionManager.getTotalAllocatedPower();
            response.totalConsumedPower = sessionManager.getTotalConsumedPower();
            
            // Power allocation map (sessionId -> allocated power)
            response.powerAllocation = sessions.stream()
                    .collect(Collectors.toMap(
                            session -> session.getSessionId(),
                            session -> session.getAllocatedPower()
                    ));
            
            // BESS status (if available)
            if (bessController != null && bessController.isBatteryAvailable()) {
                response.batteryStatus = new StationStatusResponse.BatteryStatus(
                        bessController.getSoc(),
                        bessController.getTotalCapacity(),
                        bessController.getMaxPower()
                );
            }
            
            logger.debug("Station status requested: {} sessions, {}kW allocated", 
                    response.activeSessions.size(), 
                    String.format("%.1f", response.totalAllocatedPower));
            
            return ResponseEntity.ok(response);
        } catch (Exception error) {
            logger.error("Error retrieving station status", error);
            return ResponseEntity.status(500).build();
        }
    }
    
    /**
     * Get load management summary and metrics
     * 
     * GET /api/v1/station/load-summary
     * 
     * Returns load management algorithm performance metrics:
     * - Allocation efficiency and fairness metrics
     * - Grid utilization statistics
     * - Algorithm timing and convergence data
     */
    @GetMapping("/load-summary")
    public ResponseEntity<Map<String, Object>> getLoadSummary() {
        
        try {
            var summary = loadManager.getAllocationSummary();
            
            // Add additional computed metrics
            double gridUtilization = (double) summary.get("totalAllocated") / stationConfig.gridCapacity;
            summary.put("gridUtilizationPercentage", gridUtilization * 100.0);
            
            // Fairness metrics (Jain's fairness index)
            var sessions = sessionManager.getAllSessions();
            if (!sessions.isEmpty()) {
                double[] allocations = sessions.stream()
                        .mapToDouble(session -> session.getAllocatedPower())
                        .toArray();
                double fairnessIndex = calculateJainsFairnessIndex(allocations);
                summary.put("jainsFairnessIndex", fairnessIndex);
            }
            
            return ResponseEntity.ok(summary);
        } catch (Exception error) {
            logger.error("Error retrieving load summary", error);
            return ResponseEntity.status(500).build();
        }
    }
    
    /**
     * Get station configuration
     * 
     * GET /api/v1/station/config
     * 
     * Returns current station configuration including chargers and BESS
     */
    @GetMapping("/config")
    public ResponseEntity<StationConfig> getStationConfig() {
        return ResponseEntity.ok(stationConfig);
    }
    
    /**
     * Get BESS detailed status
     * 
     * GET /api/v1/station/battery
     * 
     * Returns detailed battery information and operational state
     */
    @GetMapping("/battery")
    public ResponseEntity<Map<String, Object>> getBatteryStatus() {
        
        try {
            if (bessController == null || !bessController.isBatteryAvailable()) {
                return ResponseEntity.ok(Map.<String, Object>of(
                        "available", false,
                        "message", "No battery system configured"
                ));
            }
            
            Map<String, Object> batteryStatus = new HashMap<>();
            batteryStatus.put("available", true);
            batteryStatus.put("soc", bessController.getSoc());
            batteryStatus.put("socPercentage", bessController.getSocPercentage());
            batteryStatus.put("totalCapacity", bessController.getTotalCapacity());
            batteryStatus.put("maxPower", bessController.getMaxPower());
            batteryStatus.put("currentPower", bessController.getCurrentPower());
            batteryStatus.put("availableDischarge", bessController.getAvailableDischarge());
            batteryStatus.put("availableCharge", bessController.getAvailableCharge());
            batteryStatus.put("isEmergencyState", bessController.isEmergencyState());
            batteryStatus.put("lastUpdate", bessController.getLastUpdate().toString());
            batteryStatus.put("timestamp", System.currentTimeMillis());
            
            return ResponseEntity.ok(batteryStatus);
        } catch (Exception error) {
            logger.error("Error retrieving battery status", error);
            return ResponseEntity.status(500).build();
        }
    }
    
    /**
     * Trigger manual load recomputation
     * 
     * POST /api/v1/station/recompute
     * 
     * Forces immediate recalculation of power allocations
     * Useful for testing, debugging, or external system integration
     */
    @PostMapping("/recompute")
    public ResponseEntity<Map<String, Object>> triggerRecomputation() {
        
        logger.info("Manual load recomputation triggered");
        
        try {
            Map<String, Double> allocations = loadManager.recomputeAllocations();
            
            Map<String, Object> response = Map.<String, Object>of(
                    "success", true,
                    "allocationsComputed", allocations.size(),
                    "totalAllocatedPower", allocations.values().stream()
                            .mapToDouble(Double::doubleValue).sum(),
                    "timestamp", System.currentTimeMillis()
            );
            
            logger.info("Manual recomputation completed: {} allocations", allocations.size());
            return ResponseEntity.ok(response);
        } catch (Exception error) {
            logger.error("Error during manual recomputation", error);
            return ResponseEntity.status(500).build();
        }
    }
    
    /**
     * Health check endpoint
     * 
     * GET /api/v1/station/health
     * 
     * Returns system health status for monitoring and load balancing
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> healthCheck() {
        
        try {
            Map<String, Object> health = new HashMap<>();
            health.put("status", "UP");
            health.put("stationId", stationConfig.stationId);
            health.put("activeSessions", sessionManager.getActiveSessionCount());
            health.put("gridCapacity", stationConfig.gridCapacity);
            health.put("batteryAvailable", bessController != null && bessController.isBatteryAvailable());
            health.put("timestamp", System.currentTimeMillis());
            health.put("version", "1.0.0");
            
            return ResponseEntity.ok(health);
        } catch (Exception error) {
            logger.error("Error during health check", error);
            return ResponseEntity.status(500).build();
        }
    }
    
    /**
     * Calculate Jain's fairness index for power allocations
     * Range: 0 (unfair) to 1 (perfectly fair)
     * 
     * Formula: (Σx_i)² / (n * Σx_i²)
     */
    private double calculateJainsFairnessIndex(double[] allocations) {
        if (allocations.length == 0) return 1.0;
        
        double sum = 0.0;
        double sumSquares = 0.0;
        
        for (double allocation : allocations) {
            sum += allocation;
            sumSquares += allocation * allocation;
        }
        
        if (sumSquares == 0) return 1.0; // All zero allocations are "fair"
        
        return (sum * sum) / (allocations.length * sumSquares);
    }
}
