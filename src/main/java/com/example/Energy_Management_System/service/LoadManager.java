package com.example.Energy_Management_System.service;

import com.example.Energy_Management_System.model.ChargerConfig;
import com.example.Energy_Management_System.model.Session;
import com.example.Energy_Management_System.model.StationConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Load Management Algorithm Implementation
 * 
 * Core algorithm: Proportional Fairness with Multi-level Constraints
 * 
 * Design decisions and rationale:
 * 1. Proportional Fairness (PF): Balances fairness and efficiency, maximizes sum of log utilities
 *    Reference: IEEE VTC papers on EV charging optimization show PF performs well vs pure greedy/max-min
 * 
 * 2. Multi-level constraint handling:
 *    - Level 1: Vehicle power capability limits
 *    - Level 2: Individual charger power limits (shared across connectors)  
 *    - Level 3: Station grid capacity limits
 *    - Level 4: BESS integration for peak shaving
 * 
 * 3. Water-filling iterative algorithm: Computationally efficient O(n*log(n)) convergence
 *    Avoids need for LP solvers while achieving near-optimal allocations
 * 
 * 4. Thread-safe design: All operations can be called concurrently during power updates
 * 
 * Mathematical foundation:
 * - Objective: maximize Σ log(allocated_i) subject to capacity constraints
 * - KKT conditions lead to water-filling solution: allocated_i = min(cap_i, λ/μ_i)
 * - Iterative convergence ensures Σ allocated_i ≤ total_capacity
 */
@Service
public class LoadManager {
    
    private static final Logger logger = LoggerFactory.getLogger(LoadManager.class);
    
    // Algorithm parameters (tunable for different fairness/efficiency trade-offs)
    private static final int MAX_ITERATIONS = 20;
    private static final double CONVERGENCE_THRESHOLD = 0.01; // kW
    private static final int BINARY_SEARCH_ITERATIONS = 15;
    private static final double EPSILON = 1e-3; // Small value to avoid log(0) in utility calculation
    private static final double STATIC_LOAD = 3.0; // kW - Station auxiliary load
    private static final double GRID_SAFETY_MARGIN = 5.0; // kW - Safety margin for grid operations
    
    private final StationConfig stationConfig;
    private final SessionManager sessionManager;
    private final BessController bessController;
    
    // Caching for performance (thread-safe)
    private final Map<String, ChargerConfig> chargerMap = new ConcurrentHashMap<>();
    private volatile long lastAllocationTime = 0;
    private final Map<String, Double> lastAllocations = new ConcurrentHashMap<>();
    
    public LoadManager(StationConfig stationConfig, SessionManager sessionManager, BessController bessController) {
        this.stationConfig = stationConfig;
        this.sessionManager = sessionManager;
        this.bessController = bessController;
        
        // Pre-compute charger lookup map for O(1) access
        if (stationConfig.chargers != null) {
            stationConfig.chargers.forEach(charger -> chargerMap.put(charger.id, charger));
        }
        
        logger.info("LoadManager initialized for station: {}", stationConfig.stationId);
    }
    
    /**
     * Main entry point: Recompute power allocations for all active sessions
     * Thread-safe, can be called from multiple API endpoints concurrently
     * 
     * @return Map of session ID to allocated power
     */
    public Map<String, Double> recomputeAllocations() {
        long startTime = System.currentTimeMillis();
        
        Collection<Session> sessions = sessionManager.getAllSessions();
        if (sessions.isEmpty()) {
            logger.debug("No active sessions, clearing allocations");
            return new HashMap<>();
        }
        
        Map<String, Double> allocations = computeProportionalFairAllocations(sessions);
        
        // Apply allocations to sessions (atomic updates)
        allocations.forEach((sessionId, power) -> {
            Session session = sessionManager.getSession(sessionId);
            if (session != null) {
                session.setAllocatedPower(power);
            }
        });
        
        // Cache results
        lastAllocations.clear();
        lastAllocations.putAll(allocations);
        lastAllocationTime = System.currentTimeMillis();
        
        long duration = System.currentTimeMillis() - startTime;
        logger.debug("Load management computed {} allocations in {}ms", 
                sessions.size(), duration);
        
        // Log summary for monitoring
        if (logger.isInfoEnabled()) {
            double totalAllocated = allocations.values().stream().mapToDouble(Double::doubleValue).sum();
            logger.info("Power allocation summary: {} sessions, {}kW total", 
                    sessions.size(), String.format("%.1f", totalAllocated));
        }
        
        return allocations;
    }
    
    /**
     * Core Proportional Fairness Algorithm with Multi-level Constraints
     * 
     * Algorithm steps:
     * 1. Calculate total available power (grid + BESS - static load)
     * 2. Initialize proportional-fair allocations using water-filling
     * 3. Apply charger-level constraints (power sharing across connectors)
     * 4. Validate BESS constraints and adjust if needed
     * 5. Return final allocations
     */
    private Map<String, Double> computeProportionalFairAllocations(Collection<Session> sessions) {
        
        // Step 1: Calculate total available power
        double gridPower = stationConfig.gridCapacity - STATIC_LOAD - GRID_SAFETY_MARGIN;
        
        // Only use battery power if there's actual consumption, otherwise be conservative
        // (This logic needs to be revisited. For now, we'll revert to using allocated power for BESS decision-making.)
        double bessPower = bessController != null ? bessController.getAvailableDischarge() : 0.0;
        
        double totalAvailablePower = Math.max(0.0, gridPower + bessPower);
        
        logger.debug("Available power: grid={}kW, BESS={}kW, total={}kW", 
                gridPower, bessPower, totalAvailablePower);
        
        if (totalAvailablePower <= 0) {
            logger.warn("No power available for allocation");
            return sessions.stream().collect(Collectors.toMap(Session::getSessionId, s -> 0.0));
        }
        
        // Step 2: Build constraint map (vehicle max power per session)
        Map<Session, Double> vehicleConstraints = sessions.stream()
                .collect(Collectors.toMap(
                        session -> session,
                        Session::getVehicleMaxPower
                ));
        
        // Step 3: Initial proportional-fair water-filling allocation
        Map<Session, Double> allocations = waterFillingAllocation(
                new ArrayList<>(sessions), vehicleConstraints, totalAvailablePower);
        
        // Step 4: Apply charger-level constraints
        allocations = enforceChargerConstraints(allocations);
        
        // Step 5: Final validation and BESS adjustment
        double finalTotalPower = allocations.values().stream().mapToDouble(Double::doubleValue).sum();
        if (finalTotalPower > totalAvailablePower) {
            // Scale down proportionally if we exceeded limits after charger constraints
            double scaleFactor = totalAvailablePower / finalTotalPower;
            allocations.replaceAll((session, power) -> power * scaleFactor);
        }
        
        // Update BESS controller based on actual power draw
        // This should now use the *allocated* power for BESS decisions
        if (bessController != null && bessController.isBatteryAvailable()) {
            updateBessOperation(finalTotalPower, gridPower);
        }
        
        // Convert to session ID map for return
        return allocations.entrySet().stream()
                .collect(Collectors.toMap(
                        entry -> entry.getKey().getSessionId(),
                        Map.Entry::getValue
                ));
    }
    
    /**
     * Water-filling algorithm for proportional fairness
     * Mathematical foundation: KKT conditions for utility maximization
     * 
     * Iteratively solves: allocated_i = min(constraint_i, λ / weight_i)
     * where λ is chosen such that Σ allocated_i = totalPower
     */
    private Map<Session, Double> waterFillingAllocation(
            List<Session> sessions, 
            Map<Session, Double> constraints, 
            double totalPower) {
        
        Map<Session, Double> allocations = new HashMap<>();
        
        // Initialize with small epsilon values (avoids log(0) in utility calculation)
        sessions.forEach(session -> allocations.put(session, EPSILON));
        
        // Iterative water-filling convergence
        for (int iteration = 0; iteration < MAX_ITERATIONS; iteration++) {
            Map<Session, Double> previousAllocations = new HashMap<>(allocations);
            
            // Find optimal λ using binary search
            double lambda = findOptimalLambda(sessions, allocations, constraints, totalPower);
            
            // Update allocations based on λ
            for (Session session : sessions) {
                double currentAllocation = allocations.get(session);
                double constraint = constraints.get(session);
                
                // Water-filling formula: allocation = min(constraint, λ/weight)
                // Weight is proportional to 1/current_allocation for proportional fairness
                double weight = 1.0 / Math.max(currentAllocation, EPSILON);
                double newAllocation = Math.min(constraint, lambda / weight);
                
                allocations.put(session, Math.max(newAllocation, EPSILON));
            }
            
            // Check convergence
            boolean converged = true;
            for (Session session : sessions) {
                double change = Math.abs(allocations.get(session) - previousAllocations.get(session));
                if (change > CONVERGENCE_THRESHOLD) {
                    converged = false;
                    break;
                }
            }
            
            if (converged) {
                logger.debug("Water-filling converged after {} iterations", iteration + 1);
                break;
            }
        }
        
        return allocations;
    }
    
    /**
     * Binary search to find optimal λ parameter
     * Ensures Σ allocations = totalPower (within tolerance)
     */
    private double findOptimalLambda(
            List<Session> sessions,
            Map<Session, Double> currentAllocations,
            Map<Session, Double> constraints,
            double targetTotal) {
        
        double lambdaLow = 0.0;
        double lambdaHigh = targetTotal * 1000; // Upper bound estimate
        
        for (int i = 0; i < BINARY_SEARCH_ITERATIONS; i++) {
            double lambda = (lambdaLow + lambdaHigh) / 2.0;
            
            double totalAllocation = 0.0;
            for (Session session : sessions) {
                double currentAllocation = currentAllocations.get(session);
                double constraint = constraints.get(session);
                double weight = 1.0 / Math.max(currentAllocation, EPSILON);
                double allocation = Math.min(constraint, lambda / weight);
                totalAllocation += allocation;
            }
            
            if (totalAllocation > targetTotal) {
                lambdaHigh = lambda;
            } else {
                lambdaLow = lambda;
            }
        }
        
        return (lambdaLow + lambdaHigh) / 2.0;
    }
    
    /**
     * Enforce charger-level power constraints
     * Each charger has maxPower shared across its connectors
     */
    private Map<Session, Double> enforceChargerConstraints(Map<Session, Double> allocations) {
        Map<Session, Double> constrainedAllocations = new HashMap<>(allocations);
        
        // Group sessions by charger
        Map<String, List<Session>> sessionsByCharger = allocations.keySet().stream()
                .collect(Collectors.groupingBy(Session::getChargerId));
        
        // Apply constraints per charger
        for (Map.Entry<String, List<Session>> entry : sessionsByCharger.entrySet()) {
            String chargerId = entry.getKey();
            List<Session> chargerSessions = entry.getValue();
            
            ChargerConfig chargerConfig = chargerMap.get(chargerId);
            if (chargerConfig == null) {
                logger.warn("Unknown charger ID: {}", chargerId);
                continue;
            }
            
            // Calculate total allocation for this charger
            double chargerTotalAllocation = chargerSessions.stream()
                    .mapToDouble(allocations::get)
                    .sum();
            
            // Scale down if exceeds charger capacity
            if (chargerTotalAllocation > chargerConfig.maxPower) {
                double scaleFactor = chargerConfig.maxPower / chargerTotalAllocation;
                
                for (Session session : chargerSessions) {
                    double originalAllocation = allocations.get(session);
                    double scaledAllocation = originalAllocation * scaleFactor;
                    constrainedAllocations.put(session, scaledAllocation);
                }
                
                logger.debug("Scaled charger {} allocations by {} ({}kW -> {}kW)", 
                        chargerId, String.format("%.3f", scaleFactor), 
                        String.format("%.1f", chargerTotalAllocation), 
                        String.format("%.1f", chargerConfig.maxPower));
            }
        }
        
        return constrainedAllocations;
    }
    
    /**
     * Update BESS operation based on actual power allocation
     * Implements peak shaving strategy
     */
    private void updateBessOperation(double totalAllocatedPower, double gridCapacity) {
        if (bessController == null || !bessController.isBatteryAvailable()) {
            return;
        }
        
        double totalGridLoad = totalAllocatedPower + STATIC_LOAD;
        
        // Peak shaving: use BESS when load exceeds grid capacity
        if (totalGridLoad > gridCapacity) {
            double excessLoad = totalGridLoad - gridCapacity;
            double dischargeTime = 300; // 5 minutes typical allocation interval
            bessController.discharge(excessLoad, dischargeTime);
            
            logger.debug("BESS peak shaving: discharging {}kW", String.format("%.1f", excessLoad));
        }
        // Valley filling: charge BESS when grid has spare capacity
        else if (totalGridLoad < gridCapacity * 0.7) {
            double spareCapacity = gridCapacity - totalGridLoad;
            double chargeTime = 300;
            bessController.charge(spareCapacity * 0.5, chargeTime); // Use 50% of spare capacity
            
            logger.debug("BESS valley filling: charging {}kW", String.format("%.1f", spareCapacity * 0.5));
        }
        else {
            // Neutral zone: set BESS to idle
            bessController.setIdle();
        }
    }
    
    /**
     * Get current allocation summary for monitoring/debugging
     */
    public Map<String, Object> getAllocationSummary() {
        Map<String, Object> summary = new HashMap<>();
        
        Collection<Session> sessions = sessionManager.getAllSessions();
        double totalAllocated = sessions.stream().mapToDouble(Session::getAllocatedPower).sum();
        double totalConsumed = sessions.stream().mapToDouble(Session::getConsumedPower).sum();
        
        summary.put("totalSessions", sessions.size());
        summary.put("totalAllocated", totalAllocated);
        summary.put("totalConsumed", totalConsumed);
        summary.put("gridCapacity", stationConfig.gridCapacity);
        summary.put("gridUtilization", totalAllocated / stationConfig.gridCapacity);
        summary.put("lastAllocationTime", lastAllocationTime);
        
        if (bessController != null && bessController.isBatteryAvailable()) {
            summary.put("batterySoc", bessController.getSocPercentage());
            summary.put("batteryPower", bessController.getCurrentPower());
        }
        
        return summary;
    }
    
    /**
     * Get last computed allocations (cached for performance)
     */
    public Map<String, Double> getLastAllocations() {
        return new HashMap<>(lastAllocations);
    }
}
