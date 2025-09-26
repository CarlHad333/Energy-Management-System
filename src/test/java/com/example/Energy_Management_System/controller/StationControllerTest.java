package com.example.Energy_Management_System.controller;

import com.example.Energy_Management_System.dto.StationStatusResponse;
import com.example.Energy_Management_System.model.*;
import com.example.Energy_Management_System.service.BessController;
import com.example.Energy_Management_System.service.LoadManager;
import com.example.Energy_Management_System.service.SessionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Comprehensive edge case tests for StationController
 * 
 * Tests cover:
 * - API endpoint edge cases
 * - Error handling scenarios
 * - Status endpoint validation
 * - Invalid request handling
 * - Service integration edge cases
 * - Response format validation
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StationControllerTest {

    private StationConfig stationConfig;
    
    @Mock
    private SessionManager sessionManager;
    
    @Mock
    private LoadManager loadManager;
    
    @Mock
    private BessController bessController;
    
    private StationController stationController;

    @BeforeEach
    void setUp() {
        // Use a real StationConfig (public fields cannot be mocked with when(...))
        stationConfig = new StationConfig("TEST_STATION", 400.0, java.util.List.of(), null);
        stationController = new StationController(stationConfig, sessionManager, loadManager, bessController);
    }

    @Test
    void testGetStationStatus_Success() {
        // Given - use real Session objects to avoid mock edge cases inside mapping
        com.example.Energy_Management_System.model.Session s1 =
                new com.example.Energy_Management_System.model.Session("session1","CP001",1,150.0);
        com.example.Energy_Management_System.model.Session s2 =
                new com.example.Energy_Management_System.model.Session("session2","CP002",1,200.0);
        s1.setAllocatedPower(150.0);
        s2.setAllocatedPower(150.0);
        s1.updateConsumedPower(120.0);
        s2.updateConsumedPower(130.0);
        List<Session> sessions = Arrays.asList(s1, s2);

        when(sessionManager.getAllSessions()).thenReturn(sessions);
        when(sessionManager.getTotalAllocatedPower()).thenReturn(300.0);
        when(sessionManager.getTotalConsumedPower()).thenReturn(250.0);
        when(bessController.isBatteryAvailable()).thenReturn(true);
        when(bessController.getSoc()).thenReturn(150.0);
        when(bessController.getTotalCapacity()).thenReturn(200.0);
        when(bessController.getMaxPower()).thenReturn(100.0);

        // When
        ResponseEntity<StationStatusResponse> response = stationController.getStationStatus();

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        
        StationStatusResponse status = response.getBody();
        assertNotNull(status);
        assertEquals("TEST_STATION", status.stationId);
        assertEquals(400.0, status.gridCapacity);
        assertEquals(2, status.activeSessions.size());
        assertEquals(300.0, status.totalAllocatedPower);
        assertEquals(250.0, status.totalConsumedPower);
        assertNotNull(status.batteryStatus);
        assertEquals(150.0, status.batteryStatus.soc);
    }

    @Test
    void testGetStationStatus_NoSessions() {
        // Given
        when(sessionManager.getAllSessions()).thenReturn(Collections.emptyList());
        when(sessionManager.getTotalAllocatedPower()).thenReturn(0.0);
        when(sessionManager.getTotalConsumedPower()).thenReturn(0.0);
        when(bessController.isBatteryAvailable()).thenReturn(false);

        // When
        ResponseEntity<StationStatusResponse> response = stationController.getStationStatus();

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        StationStatusResponse status = response.getBody();
        assertNotNull(status);
        assertEquals(0, status.activeSessions.size());
        assertEquals(0.0, status.totalAllocatedPower);
        assertEquals(0.0, status.totalConsumedPower);
        assertNull(status.batteryStatus);
    }

    @Test
    void testGetStationStatus_ServiceException() {
        // Given
        when(sessionManager.getAllSessions()).thenThrow(new RuntimeException("Service error"));

        // When
        ResponseEntity<StationStatusResponse> response = stationController.getStationStatus();

        // Then
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
    }

    @Test
    void testGetLoadSummary_Success() {
        // Given
        Map<String, Object> mockSummary = new HashMap<>();
        mockSummary.put("totalAllocated", 300.0);
        mockSummary.put("algorithmTimeMs", 15.0);
        mockSummary.put("iterations", 5);
        
        when(loadManager.getAllocationSummary()).thenReturn(mockSummary);
        when(sessionManager.getAllSessions()).thenReturn(Collections.emptyList());

        // When
        ResponseEntity<Map<String, Object>> response = stationController.getLoadSummary();

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<String, Object> summary = response.getBody();
        assertNotNull(summary);
        assertEquals(300.0, summary.get("totalAllocated"));
        assertEquals(75.0, summary.get("gridUtilizationPercentage")); // 300/400 * 100
        // Fairness index is only present when there are sessions
        assertFalse(summary.containsKey("jainsFairnessIndex"));
    }

    @Test
    void testGetLoadSummary_WithSessions() {
        // Given
        Session session1 = createMockSession("session1", "CP001", 1, 100.0);
        Session session2 = createMockSession("session2", "CP002", 1, 200.0);
        when(session1.getAllocatedPower()).thenReturn(80.0);
        when(session2.getAllocatedPower()).thenReturn(120.0);
        
        Map<String, Object> mockSummary = new HashMap<>();
        mockSummary.put("totalAllocated", 200.0);
        mockSummary.put("algorithmTimeMs", 12.0);
        mockSummary.put("iterations", 3);
        
        when(loadManager.getAllocationSummary()).thenReturn(mockSummary);
        when(sessionManager.getAllSessions()).thenReturn(Arrays.asList(session1, session2));

        // When
        ResponseEntity<Map<String, Object>> response = stationController.getLoadSummary();

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<String, Object> summary = response.getBody();
        assertEquals(50.0, summary.get("gridUtilizationPercentage")); // 200/400 * 100
        
        // Jain's fairness index should be calculated
        Double fairnessIndex = (Double) summary.get("jainsFairnessIndex");
        assertNotNull(fairnessIndex);
        assertTrue(fairnessIndex > 0.0 && fairnessIndex <= 1.0);
    }

    @Test
    void testGetLoadSummary_ServiceException() {
        // Given
        when(loadManager.getAllocationSummary()).thenThrow(new RuntimeException("Load manager error"));

        // When
        ResponseEntity<Map<String, Object>> response = stationController.getLoadSummary();

        // Then
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
    }

    @Test
    void testGetStationConfig_Success() {
        // When
        ResponseEntity<StationConfig> response = stationController.getStationConfig();

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(stationConfig, response.getBody());
    }

    @Test
    void testGetBatteryStatus_Available() {
        // Given
        when(bessController.isBatteryAvailable()).thenReturn(true);
        when(bessController.getSoc()).thenReturn(150.0);
        when(bessController.getSocPercentage()).thenReturn(75.0);
        when(bessController.getTotalCapacity()).thenReturn(200.0);
        when(bessController.getMaxPower()).thenReturn(100.0);
        when(bessController.getCurrentPower()).thenReturn(50.0);
        when(bessController.getAvailableDischarge()).thenReturn(80.0);
        when(bessController.getAvailableCharge()).thenReturn(20.0);
        when(bessController.isEmergencyState()).thenReturn(false);
        when(bessController.getLastUpdate()).thenReturn(java.time.LocalDateTime.now());

        // When
        ResponseEntity<Map<String, Object>> response = stationController.getBatteryStatus();

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<String, Object> battery = response.getBody();
        assertNotNull(battery);
        assertTrue((Boolean) battery.get("available"));
        assertEquals(150.0, battery.get("soc"));
        assertEquals(75.0, battery.get("socPercentage"));
        assertEquals(200.0, battery.get("totalCapacity"));
        assertEquals(100.0, battery.get("maxPower"));
        assertEquals(50.0, battery.get("currentPower"));
        assertEquals(80.0, battery.get("availableDischarge"));
        assertEquals(20.0, battery.get("availableCharge"));
        assertFalse((Boolean) battery.get("isEmergencyState"));
        assertNotNull(battery.get("timestamp"));
    }

    @Test
    void testGetBatteryStatus_Unavailable() {
        // Given
        when(bessController.isBatteryAvailable()).thenReturn(false);

        // When
        ResponseEntity<Map<String, Object>> response = stationController.getBatteryStatus();

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<String, Object> battery = response.getBody();
        assertFalse((Boolean) battery.get("available"));
        assertEquals("No battery system configured", battery.get("message"));
    }

    @Test
    void testGetBatteryStatus_NullController() {
        // Given
        StationController controllerWithNullBess = new StationController(
            stationConfig, sessionManager, loadManager, null);

        // When
        ResponseEntity<Map<String, Object>> response = controllerWithNullBess.getBatteryStatus();

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<String, Object> battery = response.getBody();
        assertFalse((Boolean) battery.get("available"));
        assertEquals("No battery system configured", battery.get("message"));
    }

    @Test
    void testGetBatteryStatus_ServiceException() {
        // Given
        when(bessController.isBatteryAvailable()).thenThrow(new RuntimeException("BESS error"));

        // When
        ResponseEntity<Map<String, Object>> response = stationController.getBatteryStatus();

        // Then
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
    }

    @Test
    void testTriggerRecomputation_Success() {
        // Given
        Map<String, Double> allocations = Map.of(
            "session1", 100.0,
            "session2", 150.0
        );
        when(loadManager.recomputeAllocations()).thenReturn(allocations);

        // When
        ResponseEntity<Map<String, Object>> response = stationController.triggerRecomputation();

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<String, Object> result = response.getBody();
        assertTrue((Boolean) result.get("success"));
        assertEquals(2, result.get("allocationsComputed"));
        assertEquals(250.0, result.get("totalAllocatedPower"));
        assertNotNull(result.get("timestamp"));
    }

    @Test
    void testTriggerRecomputation_EmptyAllocations() {
        // Given
        when(loadManager.recomputeAllocations()).thenReturn(Collections.emptyMap());

        // When
        ResponseEntity<Map<String, Object>> response = stationController.triggerRecomputation();

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<String, Object> result = response.getBody();
        assertTrue((Boolean) result.get("success"));
        assertEquals(0, result.get("allocationsComputed"));
        assertEquals(0.0, result.get("totalAllocatedPower"));
    }

    @Test
    void testTriggerRecomputation_ServiceException() {
        // Given
        when(loadManager.recomputeAllocations()).thenThrow(new RuntimeException("Load manager error"));

        // When
        ResponseEntity<Map<String, Object>> response = stationController.triggerRecomputation();

        // Then
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
    }

    @Test
    void testHealthCheck_Success() {
        // Given
        when(sessionManager.getActiveSessionCount()).thenReturn(3);
        when(bessController.isBatteryAvailable()).thenReturn(true);

        // When
        ResponseEntity<Map<String, Object>> response = stationController.healthCheck();

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<String, Object> health = response.getBody();
        assertEquals("UP", health.get("status"));
        assertEquals("TEST_STATION", health.get("stationId"));
        assertEquals(3, health.get("activeSessions"));
        assertEquals(400.0, health.get("gridCapacity"));
        assertTrue((Boolean) health.get("batteryAvailable"));
        assertEquals("1.0.0", health.get("version"));
        assertNotNull(health.get("timestamp"));
    }

    @Test
    void testHealthCheck_NoBattery() {
        // Given
        when(sessionManager.getActiveSessionCount()).thenReturn(0);
        when(bessController.isBatteryAvailable()).thenReturn(false);

        // When
        ResponseEntity<Map<String, Object>> response = stationController.healthCheck();

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<String, Object> health = response.getBody();
        assertEquals("UP", health.get("status"));
        assertEquals(0, health.get("activeSessions"));
        assertFalse((Boolean) health.get("batteryAvailable"));
    }

    @Test
    void testHealthCheck_ServiceException() {
        // Given
        when(sessionManager.getActiveSessionCount()).thenThrow(new RuntimeException("Session manager error"));

        // When
        ResponseEntity<Map<String, Object>> response = stationController.healthCheck();

        // Then
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
    }

    @Test
    void testJainsFairnessIndexCalculation_PerfectFairness() {
        // Given - All sessions have equal allocations
        Session session1 = createMockSession("session1", "CP001", 1, 100.0);
        Session session2 = createMockSession("session2", "CP002", 1, 100.0);
        when(session1.getAllocatedPower()).thenReturn(50.0);
        when(session2.getAllocatedPower()).thenReturn(50.0);
        
        when(sessionManager.getAllSessions()).thenReturn(Arrays.asList(session1, session2));
        java.util.HashMap<String, Object> s1 = new java.util.HashMap<>();
        s1.put("totalAllocated", 100.0);
        when(loadManager.getAllocationSummary()).thenReturn(s1);

        // When
        ResponseEntity<Map<String, Object>> response = stationController.getLoadSummary();

        // Then
        Map<String, Object> summary = response.getBody();
        assertEquals(1.0, (Double) summary.get("jainsFairnessIndex"), 0.001); // Perfect fairness
    }

    @Test
    void testJainsFairnessIndexCalculation_UnfairDistribution() {
        // Given - Very unequal allocations
        Session session1 = createMockSession("session1", "CP001", 1, 100.0);
        Session session2 = createMockSession("session2", "CP002", 1, 100.0);
        when(session1.getAllocatedPower()).thenReturn(90.0);
        when(session2.getAllocatedPower()).thenReturn(10.0);
        
        when(sessionManager.getAllSessions()).thenReturn(Arrays.asList(session1, session2));
        java.util.HashMap<String, Object> s2 = new java.util.HashMap<>();
        s2.put("totalAllocated", 100.0);
        when(loadManager.getAllocationSummary()).thenReturn(s2);

        // When
        ResponseEntity<Map<String, Object>> response = stationController.getLoadSummary();

        // Then
        Map<String, Object> summary = response.getBody();
        double fairnessIndex = (Double) summary.get("jainsFairnessIndex");
        assertTrue(fairnessIndex < 1.0); // Should be less than perfect fairness
        assertTrue(fairnessIndex > 0.0); // Should be positive
    }

    @Test
    void testJainsFairnessIndexCalculation_ZeroAllocations() {
        // Given - Sessions with zero allocations
        Session session1 = createMockSession("session1", "CP001", 1, 100.0);
        Session session2 = createMockSession("session2", "CP002", 1, 100.0);
        when(session1.getAllocatedPower()).thenReturn(0.0);
        when(session2.getAllocatedPower()).thenReturn(0.0);
        
        when(sessionManager.getAllSessions()).thenReturn(Arrays.asList(session1, session2));
        java.util.HashMap<String, Object> s3 = new java.util.HashMap<>();
        s3.put("totalAllocated", 0.0);
        when(loadManager.getAllocationSummary()).thenReturn(s3);

        // When
        ResponseEntity<Map<String, Object>> response = stationController.getLoadSummary();

        // Then
        Map<String, Object> summary = response.getBody();
        assertEquals(1.0, (Double) summary.get("jainsFairnessIndex"), 0.001); // Zero allocations are "fair"
    }

    @Test
    void testJainsFairnessIndexCalculation_SingleSession() {
        // Given - Single session
        Session session1 = createMockSession("session1", "CP001", 1, 100.0);
        when(session1.getAllocatedPower()).thenReturn(50.0);
        
        when(sessionManager.getAllSessions()).thenReturn(Collections.singletonList(session1));
        java.util.HashMap<String, Object> s4 = new java.util.HashMap<>();
        s4.put("totalAllocated", 50.0);
        when(loadManager.getAllocationSummary()).thenReturn(s4);

        // When
        ResponseEntity<Map<String, Object>> response = stationController.getLoadSummary();

        // Then
        Map<String, Object> summary = response.getBody();
        assertEquals(1.0, (Double) summary.get("jainsFairnessIndex"), 0.001); // Single session is "fair"
    }

    // Helper method to create mock sessions
    private Session createMockSession(String sessionId, String chargerId, int connectorId, double vehicleMaxPower) {
        Session session = mock(Session.class);
        when(session.getSessionId()).thenReturn(sessionId);
        when(session.getChargerId()).thenReturn(chargerId);
        when(session.getConnectorId()).thenReturn(connectorId);
        when(session.getVehicleMaxPower()).thenReturn(vehicleMaxPower);
        when(session.getConnectorKey()).thenReturn(chargerId + "-" + connectorId);
        when(session.getAllocatedPower()).thenReturn(0.0);
        when(session.getConsumedPower()).thenReturn(0.0);
        return session;
    }
}
