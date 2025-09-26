package com.example.Energy_Management_System.service;

import com.example.Energy_Management_System.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Comprehensive edge case tests for LoadManager
 * 
 * Tests cover:
 * - Zero sessions scenario
 * - Single session edge cases
 * - Exact capacity limits
 * - Fairness validation
 * - Grid constraint violations
 * - BESS integration edge cases
 * - Thread safety scenarios
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LoadManagerTest {

    @Mock
    private SessionManager sessionManager;
    
    @Mock
    private BessController bessController;
    
    private LoadManager loadManager;
    private StationConfig stationConfig;
    
    @BeforeEach
    void setUp() {
        // Create real StationConfig instead of mocking
        List<ChargerConfig> chargers = Arrays.asList(
            new ChargerConfig("CP001", 200.0, 2),
            new ChargerConfig("CP002", 200.0, 2),
            new ChargerConfig("CP003", 300.0, 2)
        );
        
        stationConfig = new StationConfig("TEST_STATION", 400.0, chargers, null);
        loadManager = new LoadManager(stationConfig, sessionManager, bessController);
    }

    @Test
    void testZeroSessions_ShouldReturnEmptyMap() {
        // Given
        when(sessionManager.getAllSessions()).thenReturn(Collections.emptyList());
        
        // When
        Map<String, Double> allocations = loadManager.recomputeAllocations();
        
        // Then
        assertTrue(allocations.isEmpty());
        verify(sessionManager).getAllSessions();
    }

    @Test
    void testSingleSession_ShouldAllocateFullCapacity() {
        // Given
        Session session = createMockSession("session1", "CP001", 1, 150.0);
        when(sessionManager.getAllSessions()).thenReturn(Collections.singletonList(session));
        when(sessionManager.getSession("session1")).thenReturn(session);
        
        // When
        Map<String, Double> allocations = loadManager.recomputeAllocations();
        
        // Then
        assertEquals(1, allocations.size());
        assertEquals(150.0, allocations.get("session1"), 0.1);
        verify(session).setAllocatedPower(150.0);
    }

    @Test
    void testExactGridCapacity_ShouldRespectLimit() {
        // Given - Two sessions requesting exactly grid capacity
        Session session1 = createMockSession("session1", "CP001", 1, 200.0);
        Session session2 = createMockSession("session2", "CP002", 1, 200.0);
        when(sessionManager.getAllSessions()).thenReturn(Arrays.asList(session1, session2));
        when(sessionManager.getSession("session1")).thenReturn(session1);
        when(sessionManager.getSession("session2")).thenReturn(session2);
        
        // When
        Map<String, Double> allocations = loadManager.recomputeAllocations();
        
        // Then
        double totalAllocated = allocations.values().stream().mapToDouble(Double::doubleValue).sum();
        assertTrue(totalAllocated <= 400.0, "Total allocation should not exceed grid capacity");
        assertTrue(totalAllocated >= 390.0, "Should utilize most of grid capacity");
    }

    @Test
    void testChargerCapacityLimit_ShouldRespectIndividualChargerLimits() {
        // Given - Two sessions on same charger exceeding charger capacity
        Session session1 = createMockSession("session1", "CP001", 1, 150.0);
        Session session2 = createMockSession("session2", "CP001", 2, 150.0);
        when(sessionManager.getAllSessions()).thenReturn(Arrays.asList(session1, session2));
        when(sessionManager.getSession("session1")).thenReturn(session1);
        when(sessionManager.getSession("session2")).thenReturn(session2);
        
        // When
        Map<String, Double> allocations = loadManager.recomputeAllocations();
        
        // Then
        double totalOnCharger = allocations.values().stream().mapToDouble(Double::doubleValue).sum();
        assertTrue(totalOnCharger <= 200.0, "Total allocation on CP001 should not exceed 200kW");
        
        // Should be fair split
        double session1Allocation = allocations.get("session1");
        double session2Allocation = allocations.get("session2");
        assertEquals(session1Allocation, session2Allocation, 1.0, "Should be fair split");
    }

    @Test
    void testFairnessWithDifferentVehicleCapacities() {
        // Given - Sessions with different max power capabilities
        Session session1 = createMockSession("session1", "CP001", 1, 50.0);   // Low power vehicle
        Session session2 = createMockSession("session2", "CP002", 1, 200.0);  // High power vehicle
        when(sessionManager.getAllSessions()).thenReturn(Arrays.asList(session1, session2));
        when(sessionManager.getSession("session1")).thenReturn(session1);
        when(sessionManager.getSession("session2")).thenReturn(session2);
        
        // When
        Map<String, Double> allocations = loadManager.recomputeAllocations();
        
        // Then
        double session1Allocation = allocations.get("session1");
        double session2Allocation = allocations.get("session2");
        
        // Proportional fairness should favor the lower-capacity vehicle
        assertTrue(session1Allocation > session2Allocation * 0.2, 
                "Low power vehicle should get reasonable allocation");
        assertTrue(session2Allocation > session1Allocation, 
                "High power vehicle should still get more power");
    }

    @Test
    void testBessIntegration_ShouldProvideAdditionalCapacity() {
        // Given - BESS available with discharge capability
        Session session1 = createMockSession("session1", "CP001", 1, 200.0);
        Session session2 = createMockSession("session2", "CP002", 1, 200.0);
        when(sessionManager.getAllSessions()).thenReturn(Arrays.asList(session1, session2));
        when(sessionManager.getSession("session1")).thenReturn(session1);
        when(sessionManager.getSession("session2")).thenReturn(session2);
        
        when(bessController.isBatteryAvailable()).thenReturn(true);
        when(bessController.getAvailableDischarge()).thenReturn(100.0);
        
        // When
        Map<String, Double> allocations = loadManager.recomputeAllocations();
        
        // Then
        double totalAllocated = allocations.values().stream().mapToDouble(Double::doubleValue).sum();
        // With BESS, total allocated should be at least grid capacity and may increase
        assertTrue(totalAllocated >= 400.0, "Should utilize at least grid capacity with BESS support");
        assertTrue(totalAllocated <= 500.0, "Should not exceed grid + BESS capacity");
    }

    @Test
    void testBessUnavailable_ShouldRespectGridCapacityOnly() {
        // Given - BESS unavailable
        Session session1 = createMockSession("session1", "CP001", 1, 200.0);
        Session session2 = createMockSession("session2", "CP002", 1, 200.0);
        when(sessionManager.getAllSessions()).thenReturn(Arrays.asList(session1, session2));
        when(sessionManager.getSession("session1")).thenReturn(session1);
        when(sessionManager.getSession("session2")).thenReturn(session2);
        
        when(bessController.isBatteryAvailable()).thenReturn(false);
        when(bessController.getAvailableDischarge()).thenReturn(0.0);
        
        // When
        Map<String, Double> allocations = loadManager.recomputeAllocations();
        
        // Then
        double totalAllocated = allocations.values().stream().mapToDouble(Double::doubleValue).sum();
        assertTrue(totalAllocated <= 400.0, "Should respect grid capacity when BESS unavailable");
    }

    @Test
    void testLowBessSoc_ShouldLimitDischarge() {
        // Given - BESS with low SOC
        Session session1 = createMockSession("session1", "CP001", 1, 200.0);
        Session session2 = createMockSession("session2", "CP002", 1, 200.0);
        when(sessionManager.getAllSessions()).thenReturn(Arrays.asList(session1, session2));
        when(sessionManager.getSession("session1")).thenReturn(session1);
        when(sessionManager.getSession("session2")).thenReturn(session2);
        
        when(bessController.isBatteryAvailable()).thenReturn(true);
        when(bessController.getAvailableDischarge()).thenReturn(10.0); // Very limited discharge
        
        // When
        Map<String, Double> allocations = loadManager.recomputeAllocations();
        
        // Then
        double totalAllocated = allocations.values().stream().mapToDouble(Double::doubleValue).sum();
        assertTrue(totalAllocated <= 410.0, "Should respect limited BESS discharge");
    }

    @Test
    void testConvergenceWithManySessions() {
        // Given - Many sessions to test algorithm convergence
        List<Session> sessions = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            Session session = createMockSession("session" + i, "CP001", i % 2 + 1, 50.0);
            sessions.add(session);
            when(sessionManager.getSession("session" + i)).thenReturn(session);
        }
        when(sessionManager.getAllSessions()).thenReturn(sessions);
        
        // When
        Map<String, Double> allocations = loadManager.recomputeAllocations();
        
        // Then
        assertEquals(10, allocations.size());
        double totalAllocated = allocations.values().stream().mapToDouble(Double::doubleValue).sum();
        assertTrue(totalAllocated <= 200.0, "Should respect charger capacity");
        
        // All allocations should be positive
        allocations.values().forEach(allocation -> 
            assertTrue(allocation > 0, "All allocations should be positive"));
    }

    @Test
    void testEdgeCaseZeroVehiclePower() {
        // Given - Session with zero vehicle power
        Session session = createMockSession("session1", "CP001", 1, 0.0);
        when(sessionManager.getAllSessions()).thenReturn(Collections.singletonList(session));
        when(sessionManager.getSession("session1")).thenReturn(session);
        
        // When
        Map<String, Double> allocations = loadManager.recomputeAllocations();
        
        // Then
        assertEquals(0.0, allocations.get("session1"), 0.01);
    }

    @Test
    void testEdgeCaseVeryHighVehiclePower() {
        // Given - Session requesting more than total grid capacity
        Session session = createMockSession("session1", "CP001", 1, 1000.0);
        when(sessionManager.getAllSessions()).thenReturn(Collections.singletonList(session));
        when(sessionManager.getSession("session1")).thenReturn(session);
        
        // When
        Map<String, Double> allocations = loadManager.recomputeAllocations();
        
        // Then
        double allocation = allocations.get("session1");
        assertTrue(allocation <= 200.0, "Should be limited by charger capacity");
        assertTrue(allocation > 0, "Should still get some allocation");
    }

    @Test
    void testThreadSafety_ConcurrentAllocations() throws InterruptedException {
        // Given
        Session session1 = createMockSession("session1", "CP001", 1, 100.0);
        Session session2 = createMockSession("session2", "CP002", 1, 100.0);
        when(sessionManager.getAllSessions()).thenReturn(Arrays.asList(session1, session2));
        when(sessionManager.getSession("session1")).thenReturn(session1);
        when(sessionManager.getSession("session2")).thenReturn(session2);
        
        // When - Run concurrent allocations
        List<Thread> threads = new ArrayList<>();
        List<Map<String, Double>> results = Collections.synchronizedList(new ArrayList<>());
        
        for (int i = 0; i < 10; i++) {
            Thread thread = new Thread(() -> {
                Map<String, Double> allocation = loadManager.recomputeAllocations();
                results.add(allocation);
            });
            threads.add(thread);
            thread.start();
        }
        
        for (Thread thread : threads) {
            thread.join();
        }
        
        // Then - All results should be consistent
        assertEquals(10, results.size());
        Map<String, Double> firstResult = results.get(0);
        for (Map<String, Double> result : results) {
            assertEquals(firstResult, result, "Concurrent allocations should be consistent");
        }
    }

    @Test
    void testAllocationSummary_ShouldReturnValidMetrics() {
        // Given
        Session session1 = createMockSession("session1", "CP001", 1, 100.0);
        Session session2 = createMockSession("session2", "CP002", 1, 100.0);
        when(sessionManager.getAllSessions()).thenReturn(Arrays.asList(session1, session2));
        when(sessionManager.getSession("session1")).thenReturn(session1);
        when(sessionManager.getSession("session2")).thenReturn(session2);
        
        // When
        loadManager.recomputeAllocations();
        Map<String, Object> summary = loadManager.getAllocationSummary();
        
        // Then
        assertNotNull(summary);
        assertTrue(summary.containsKey("totalAllocated"));
        assertTrue(summary.containsKey("algorithmTimeMs"));
        assertTrue(summary.containsKey("iterations"));
        
        Object totalAllocatedObj = summary.get("totalAllocated");
        assertNotNull(totalAllocatedObj);
    }

    // Helper method to create mock sessions
    private Session createMockSession(String sessionId, String chargerId, int connectorId, double vehicleMaxPower) {
        Session session = mock(Session.class);
        when(session.getSessionId()).thenReturn(sessionId);
        when(session.getChargerId()).thenReturn(chargerId);
        when(session.getConnectorId()).thenReturn(connectorId);
        when(session.getVehicleMaxPower()).thenReturn(vehicleMaxPower);
        when(session.getConnectorKey()).thenReturn(chargerId + "-" + connectorId);
        return session;
    }
}