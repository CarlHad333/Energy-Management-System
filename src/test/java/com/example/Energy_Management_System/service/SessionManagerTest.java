package com.example.Energy_Management_System.service;

import com.example.Energy_Management_System.model.Session;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive edge case tests for SessionManager
 * 
 * Tests cover:
 * - Connector double booking prevention
 * - Session state transitions
 * - Concurrent session operations
 * - Session lifecycle edge cases
 * - Thread safety scenarios
 * - Invalid input handling
 */
class SessionManagerTest {

    private SessionManager sessionManager;

    @BeforeEach
    void setUp() {
        sessionManager = new SessionManager();
    }

    @Test
    void testStartSession_SuccessfulCreation() {
        // When
        Session session = sessionManager.startSession("CP001", 1, 150.0);

        // Then
        assertNotNull(session);
        assertEquals("CP001", session.getChargerId());
        assertEquals(1, session.getConnectorId());
        assertEquals(150.0, session.getVehicleMaxPower());
        assertEquals(Session.State.ACTIVE, session.getState());
    }

    @Test
    void testStartSession_DoubleBookingPrevention() {
        // Given - First session on connector
        Session session1 = sessionManager.startSession("CP001", 1, 150.0);
        assertNotNull(session1);

        // When - Attempt to start second session on same connector
        Session session2 = sessionManager.startSession("CP001", 1, 100.0);

        // Then
        assertNull(session2, "Second session should be rejected due to connector occupation");
    }

    @Test
    void testStartSession_DifferentConnectorsSameCharger() {
        // When - Start sessions on different connectors of same charger
        Session session1 = sessionManager.startSession("CP001", 1, 150.0);
        Session session2 = sessionManager.startSession("CP001", 2, 100.0);

        // Then
        assertNotNull(session1);
        assertNotNull(session2);
        assertNotEquals(session1.getSessionId(), session2.getSessionId());
    }

    @Test
    void testStartSession_DifferentChargersSameConnectorId() {
        // When - Start sessions on same connector ID but different chargers
        Session session1 = sessionManager.startSession("CP001", 1, 150.0);
        Session session2 = sessionManager.startSession("CP002", 1, 100.0);

        // Then
        assertNotNull(session1);
        assertNotNull(session2);
        assertNotEquals(session1.getSessionId(), session2.getSessionId());
    }

    @Test
    void testStopSession_SuccessfulStop() {
        // Given
        Session session = sessionManager.startSession("CP001", 1, 150.0);
        assertNotNull(session);
        String sessionId = session.getSessionId();

        // When
        boolean stopped = sessionManager.stopSession(sessionId);

        // Then
        assertTrue(stopped);
        assertEquals(Session.State.STOPPING, session.getState());
        assertNull(sessionManager.getSession(sessionId));
    }

    @Test
    void testStopSession_NonExistentSession() {
        // When
        boolean stopped = sessionManager.stopSession("non-existent-session");

        // Then
        assertFalse(stopped);
    }

    @Test
    void testStopSession_ConnectorBecomesAvailable() {
        // Given
        Session session = sessionManager.startSession("CP001", 1, 150.0);
        String sessionId = session.getSessionId();

        // When
        sessionManager.stopSession(sessionId);

        // Then - Connector should be available for new session
        Session newSession = sessionManager.startSession("CP001", 1, 100.0);
        assertNotNull(newSession);
        assertNotEquals(sessionId, newSession.getSessionId());
    }

    @Test
    void testGetSession_ExistingSession() {
        // Given
        Session session = sessionManager.startSession("CP001", 1, 150.0);
        String sessionId = session.getSessionId();

        // When
        Session retrieved = sessionManager.getSession(sessionId);

        // Then
        assertNotNull(retrieved);
        assertEquals(sessionId, retrieved.getSessionId());
        assertEquals(session, retrieved);
    }

    @Test
    void testGetSession_NonExistentSession() {
        // When
        Session retrieved = sessionManager.getSession("non-existent-session");

        // Then
        assertNull(retrieved);
    }

    @Test
    void testGetAllSessions_EmptyManager() {
        // When
        Collection<Session> sessions = sessionManager.getAllSessions();

        // Then
        assertTrue(sessions.isEmpty());
    }

    @Test
    void testGetAllSessions_MultipleSessions() {
        // Given
        Session session1 = sessionManager.startSession("CP001", 1, 150.0);
        Session session2 = sessionManager.startSession("CP001", 2, 100.0);
        Session session3 = sessionManager.startSession("CP002", 1, 200.0);

        // When
        Collection<Session> sessions = sessionManager.getAllSessions();

        // Then
        assertEquals(3, sessions.size());
        assertTrue(sessions.contains(session1));
        assertTrue(sessions.contains(session2));
        assertTrue(sessions.contains(session3));
    }

    @Test
    void testGetAllSessions_AfterStop() {
        // Given
        Session session1 = sessionManager.startSession("CP001", 1, 150.0);
        Session session2 = sessionManager.startSession("CP001", 2, 100.0);
        sessionManager.stopSession(session1.getSessionId());

        // When
        Collection<Session> sessions = sessionManager.getAllSessions();

        // Then
        assertEquals(1, sessions.size());
        assertTrue(sessions.contains(session2));
        assertFalse(sessions.contains(session1));
    }

    @Test
    void testGetActiveSessionCount() {
        // Given
        assertEquals(0, sessionManager.getActiveSessionCount());

        // When
        Session session1 = sessionManager.startSession("CP001", 1, 150.0);
        assertEquals(1, sessionManager.getActiveSessionCount());

        Session session2 = sessionManager.startSession("CP001", 2, 100.0);
        assertEquals(2, sessionManager.getActiveSessionCount());

        sessionManager.stopSession(session1.getSessionId());
        assertEquals(1, sessionManager.getActiveSessionCount());

        sessionManager.stopSession(session2.getSessionId());
        assertEquals(0, sessionManager.getActiveSessionCount());
    }

    @Test
    void testGetTotalAllocatedPower() {
        // Given
        assertEquals(0.0, sessionManager.getTotalAllocatedPower());

        Session session1 = sessionManager.startSession("CP001", 1, 150.0);
        session1.setAllocatedPower(100.0);

        Session session2 = sessionManager.startSession("CP001", 2, 200.0);
        session2.setAllocatedPower(150.0);

        // When
        double totalPower = sessionManager.getTotalAllocatedPower();

        // Then
        assertEquals(250.0, totalPower, 0.01);
    }

    @Test
    void testGetTotalConsumedPower() {
        // Given
        assertEquals(0.0, sessionManager.getTotalConsumedPower());

        Session session1 = sessionManager.startSession("CP001", 1, 150.0);
        session1.updateConsumedPower(80.0);

        Session session2 = sessionManager.startSession("CP001", 2, 200.0);
        session2.updateConsumedPower(120.0);

        // When
        double totalConsumed = sessionManager.getTotalConsumedPower();

        // Then
        assertEquals(200.0, totalConsumed, 0.01);
    }

    @Test
    void testConcurrentSessionCreation() throws InterruptedException {
        // Given
        int numberOfThreads = 10;
        ExecutorService executor = Executors.newFixedThreadPool(numberOfThreads);
        CountDownLatch latch = new CountDownLatch(numberOfThreads);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        // When - Concurrent session creation attempts
        for (int i = 0; i < numberOfThreads; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    Session session = sessionManager.startSession("CP001", threadId % 2 + 1, 100.0);
                    if (session != null) {
                        successCount.incrementAndGet();
                    } else {
                        failureCount.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        // Then - Should have exactly 2 successful sessions (2 connectors)
        assertEquals(2, successCount.get());
        assertEquals(8, failureCount.get());
        assertEquals(2, sessionManager.getActiveSessionCount());
    }

    @Test
    void testConcurrentStartAndStop() throws InterruptedException {
        // Given
        Session session = sessionManager.startSession("CP001", 1, 150.0);
        String sessionId = session.getSessionId();
        
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch latch = new CountDownLatch(2);
        AtomicInteger stopSuccessCount = new AtomicInteger(0);

        // When - Concurrent stop attempts
        for (int i = 0; i < 2; i++) {
            executor.submit(() -> {
                try {
                    boolean stopped = sessionManager.stopSession(sessionId);
                    if (stopped) {
                        stopSuccessCount.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        // Then - Only one stop should succeed
        assertEquals(1, stopSuccessCount.get());
        assertEquals(0, sessionManager.getActiveSessionCount());
    }

    @Test
    void testEdgeCase_ZeroVehiclePower() {
        // When
        Session session = sessionManager.startSession("CP001", 1, 0.0);

        // Then
        assertNotNull(session);
        assertEquals(0.0, session.getVehicleMaxPower());
    }

    @Test
    void testEdgeCase_VeryHighVehiclePower() {
        // When
        Session session = sessionManager.startSession("CP001", 1, 1000.0);

        // Then
        assertNotNull(session);
        assertEquals(1000.0, session.getVehicleMaxPower());
    }

    @Test
    void testEdgeCase_InvalidConnectorId() {
        // When - Connector ID 0 (should be 1-based)
        Session session = sessionManager.startSession("CP001", 0, 150.0);

        // Then
        assertNotNull(session); // Should still work, connector ID validation is not enforced
        assertEquals(0, session.getConnectorId());
    }

    @Test
    void testEdgeCase_NegativeConnectorId() {
        // When
        Session session = sessionManager.startSession("CP001", -1, 150.0);

        // Then
        assertNotNull(session); // Should still work
        assertEquals(-1, session.getConnectorId());
    }

    @Test
    void testSessionIdUniqueness() {
        // Given
        List<String> sessionIds = new java.util.ArrayList<>();

        // When - Create multiple sessions
        for (int i = 0; i < 100; i++) {
            Session session = sessionManager.startSession("CP001", i % 2 + 1, 100.0);
            if (session != null) {
                sessionIds.add(session.getSessionId());
            }
        }

        // Then - All session IDs should be unique
        assertEquals(sessionIds.size(), sessionIds.stream().distinct().count());
    }

    @Test
    void testSessionStateTransitions() {
        // Given
        Session session = sessionManager.startSession("CP001", 1, 150.0);
        assertEquals(Session.State.ACTIVE, session.getState());

        // When - Stop session
        sessionManager.stopSession(session.getSessionId());

        // Then
        assertEquals(Session.State.STOPPING, session.getState());
    }

    @Test
    void testMultipleStopsOnSameSession() {
        // Given
        Session session = sessionManager.startSession("CP001", 1, 150.0);
        String sessionId = session.getSessionId();

        // When - First stop
        boolean firstStop = sessionManager.stopSession(sessionId);
        assertTrue(firstStop);

        // When - Second stop attempt
        boolean secondStop = sessionManager.stopSession(sessionId);

        // Then
        assertFalse(secondStop, "Second stop should fail");
    }

    @Test
    void testConnectorAvailabilityAfterStop() {
        // Given
        Session session = sessionManager.startSession("CP001", 1, 150.0);
        String sessionId = session.getSessionId();

        // When - Stop session
        sessionManager.stopSession(sessionId);

        // Then - Connector should be available for new session
        Session newSession = sessionManager.startSession("CP001", 1, 100.0);
        assertNotNull(newSession);
        assertNotEquals(sessionId, newSession.getSessionId());
    }

    @Test
    void testPowerUpdateOperations() {
        // Given
        Session session = sessionManager.startSession("CP001", 1, 150.0);

        // When - Update allocated power
        session.setAllocatedPower(100.0);
        assertEquals(100.0, session.getAllocatedPower(), 0.01);

        // When - Update consumed power
        session.updateConsumedPower(80.0);
        assertEquals(80.0, session.getConsumedPower(), 0.01);

        // When - Update vehicle max power
        session.updateVehicleMaxPower(200.0);
        assertEquals(200.0, session.getVehicleMaxPower(), 0.01);
    }
}
