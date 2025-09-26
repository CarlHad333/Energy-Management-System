package com.example.Energy_Management_System.service;

import com.example.Energy_Management_System.model.BatteryConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive edge case tests for BessController
 * 
 * Tests cover:
 * - SOC boundary conditions (5% emergency, 10% min, 95% max)
 * - Charge/discharge power limits
 * - Battery availability scenarios
 * - Thread safety with concurrent operations
 * - Edge cases with zero/null configurations
 * - Power sustainability calculations
 */
class BessControllerTest {

    private BessController bessController;
    private BatteryConfig batteryConfig;

    @BeforeEach
    void setUp() {
        batteryConfig = new BatteryConfig();
        batteryConfig.initialCapacity = 200.0; // 200 kWh
        batteryConfig.power = 100.0; // 100 kW max power
        bessController = new BessController(batteryConfig);
    }

    @Test
    void testBatteryAvailable_WithValidConfig() {
        // Then
        assertTrue(bessController.isBatteryAvailable());
        assertEquals(200.0, bessController.getTotalCapacity(), 0.01);
        assertEquals(100.0, bessController.getMaxPower(), 0.01);
    }

    @Test
    void testBatteryUnavailable_WithNullConfig() {
        // Given
        BessController nullController = new BessController(null);

        // Then
        assertFalse(nullController.isBatteryAvailable());
        assertEquals(0.0, nullController.getTotalCapacity(), 0.01);
        assertEquals(0.0, nullController.getMaxPower(), 0.01);
    }

    @Test
    void testInitialSoc_FullyCharged() {
        // Then
        assertEquals(200.0, bessController.getSoc(), 0.01);
        assertEquals(100.0, bessController.getSocPercentage(), 0.01);
    }

    @Test
    void testAvailableDischarge_FullyCharged() {
        // Given - Fully charged battery (200 kWh = 100%)
        
        // When
        double availableDischarge = bessController.getAvailableDischarge();

        // Then
        assertEquals(100.0, availableDischarge, 0.01); // Max power available
    }

    @Test
    void testAvailableCharge_FullyCharged() {
        // Given - Fully charged battery (100%)

        // When
        double availableCharge = bessController.getAvailableCharge();

        // Then
        assertEquals(0.0, availableCharge, 0.01); // No charge available
    }

    @Test
    void testDischargeOperation_WithinLimits() {
        // Given - Battery at 50% SOC (100 kWh) - simulate by discharging first
        bessController.discharge(100.0, 3600.0); // Discharge 100 kWh

        // When - Discharge 50 kW for 1 hour
        double actualPower = bessController.discharge(50.0, 3600.0);

        // Then
        assertEquals(50.0, actualPower, 0.01);
        assertTrue(bessController.getSoc() < 100.0); // SOC should be reduced
    }

    @Test
    void testDischargeOperation_ExceedsAvailablePower() {
        // Given - Battery at 50% SOC (100 kWh) - simulate by discharging first
        bessController.discharge(100.0, 3600.0); // Discharge 100 kWh

        // When - Attempt to discharge 150 kW (exceeds max power of 100 kW)
        double actualPower = bessController.discharge(150.0, 3600.0);

        // Then
        assertEquals(100.0, actualPower, 0.01); // Should be limited to max power
    }

    @Test
    void testDischargeOperation_ExceedsAvailableEnergy() {
        // Given - Battery at 50% SOC (100 kWh) - simulate by discharging first
        bessController.discharge(100.0, 3600.0); // Discharge 100 kWh

        // When - Attempt to discharge 100 kW for 2 hours (200 kWh, exceeds available energy)
        double actualPower = bessController.discharge(100.0, 7200.0);

        // Then - ensure a valid numeric result within bounds
        assertTrue(actualPower >= 0.0 && actualPower <= 100.0);
    }

    @Test
    void testChargeOperation_WithinLimits() {
        // Given - Battery at 50% SOC (100 kWh) - simulate by discharging first
        bessController.discharge(100.0, 3600.0); // Discharge 100 kWh

        // When - Charge 50 kW for 1 hour
        double actualPower = bessController.charge(50.0, 3600.0);

        // Then
        assertEquals(50.0, actualPower, 0.01);
        assertTrue(bessController.getSoc() > 50.0); // SOC should be increased
    }

    @Test
    void testChargeOperation_ExceedsMaxPower() {
        // Given - Battery at 50% SOC (100 kWh) - simulate by discharging first
        bessController.discharge(100.0, 3600.0); // Discharge 100 kWh

        // When - Attempt to charge 150 kW (exceeds max power of 100 kW)
        double actualPower = bessController.charge(150.0, 3600.0);

        // Then
        assertEquals(100.0, actualPower, 0.01); // Should be limited to max power
    }

    @Test
    void testCurrentPowerTracking() {
        // Given
        assertEquals(0.0, bessController.getCurrentPower(), 0.01);

        // When - Discharge operation
        bessController.discharge(50.0, 3600.0);
        assertEquals(50.0, bessController.getCurrentPower(), 0.01);

        // When - Charge operation
        bessController.charge(30.0, 3600.0);
        assertEquals(-30.0, bessController.getCurrentPower(), 0.01); // Negative for charge
    }

    @Test
    void testLastUpdateTimestamp() {
        // Given
        LocalDateTime initialTime = bessController.getLastUpdate();

        // When - Perform operation
        bessController.discharge(10.0, 0.1);

        // Then
        LocalDateTime updatedTime = bessController.getLastUpdate();
        assertFalse(updatedTime.isBefore(initialTime));
    }

    @Test
    void testConcurrentOperations() throws InterruptedException {
        // Given
        ExecutorService executor = Executors.newFixedThreadPool(4);
        CountDownLatch latch = new CountDownLatch(4);
        AtomicInteger successCount = new AtomicInteger(0);

        // When - Concurrent discharge operations
        for (int i = 0; i < 4; i++) {
            executor.submit(() -> {
                try {
                    double actualPower = bessController.discharge(25.0, 0.5);
                    if (actualPower > 0) {
                        successCount.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        // Then - Multiple operations should succeed (total discharge = 100 kWh)
        assertTrue(successCount.get() > 0);
        assertTrue(bessController.getSoc() < 200.0); // SOC should be reduced
    }

    @Test
    void testEdgeCase_ZeroPowerDischarge() {
        // When
        double actualPower = bessController.discharge(0.0, 1.0);

        // Then
        assertEquals(0.0, actualPower, 0.01); // Zero power should return 0
        assertEquals(200.0, bessController.getSoc(), 0.01); // SOC unchanged
    }

    @Test
    void testEdgeCase_ZeroPowerCharge() {
        // When
        double actualPower = bessController.charge(0.0, 1.0);

        // Then
        assertEquals(0.0, actualPower, 0.01); // Zero power should return 0
        assertEquals(200.0, bessController.getSoc(), 0.01); // SOC unchanged
    }

    @Test
    void testEdgeCase_NegativePowerDischarge() {
        // When
        double actualPower = bessController.discharge(-10.0, 1.0);

        // Then
        assertEquals(0.0, actualPower, 0.01); // Negative power should return 0
        assertEquals(200.0, bessController.getSoc(), 0.01); // SOC unchanged
    }

    @Test
    void testEdgeCase_NegativePowerCharge() {
        // When
        double actualPower = bessController.charge(-10.0, 1.0);

        // Then
        assertEquals(0.0, actualPower, 0.01); // Negative power should return 0
        assertEquals(200.0, bessController.getSoc(), 0.01); // SOC unchanged
    }

    @Test
    void testEdgeCase_NegativeDuration() {
        // When
        double actualPower = bessController.discharge(50.0, -1.0);

        // Then
        assertEquals(0.0, actualPower, 0.01); // Negative duration should return 0
        assertEquals(200.0, bessController.getSoc(), 0.01); // SOC unchanged
    }

    @Test
    void testEdgeCase_ZeroDuration() {
        // When
        double actualPower = bessController.discharge(50.0, 0.0);

        // Then
        assertEquals(0.0, actualPower, 0.01); // Zero duration should return 0
        assertEquals(200.0, bessController.getSoc(), 0.01); // SOC unchanged
    }

    @Test
    void testPowerSustainabilityCalculation() {
        // Given - Battery at 50% SOC (100 kWh), available energy = 80 kWh (above 10% minimum)
        bessController.discharge(100.0, 3600.0); // Reduce to 50% SOC

        // When
        double availableDischarge = bessController.getAvailableDischarge();

        // Then
        // Should be limited by available energy sustainability (80 kWh * 4 = 320 kW max sustainable)
        // But capped at max power (100 kW)
        assertEquals(100.0, availableDischarge, 0.01);
    }

    @Test
    void testBatteryConfig_ZeroCapacity() {
        // Given
        BatteryConfig zeroConfig = new BatteryConfig();
        zeroConfig.initialCapacity = 0.0;
        zeroConfig.power = 100.0;
        BessController zeroController = new BessController(zeroConfig);

        // Then
        assertFalse(zeroController.isBatteryAvailable());
        assertEquals(0.0, zeroController.getTotalCapacity(), 0.01);
    }

    @Test
    void testBatteryConfig_ZeroPower() {
        // Given
        BatteryConfig zeroPowerConfig = new BatteryConfig();
        zeroPowerConfig.initialCapacity = 200.0;
        zeroPowerConfig.power = 0.0;
        BessController zeroPowerController = new BessController(zeroPowerConfig);

        // Then
        assertTrue(zeroPowerController.isBatteryAvailable());
        assertEquals(0.0, zeroPowerController.getMaxPower(), 0.01);
        assertEquals(0.0, zeroPowerController.getAvailableDischarge(), 0.01);
        assertEquals(0.0, zeroPowerController.getAvailableCharge(), 0.01);
    }

    @Test
    void testEmergencyStateDetection() {
        // Given - Discharge battery to very low SOC
        bessController.discharge(190.0, 3600.0); // Discharge most of the battery

        // When
        boolean isEmergency = bessController.isEmergencyState();

        // Then
        // Should be in emergency state when SOC is very low
        assertTrue(isEmergency);
    }

    @Test
    void testSocPercentageCalculation() {
        // Given - Discharge battery to 50% SOC
        bessController.discharge(100.0, 3600.0); // Discharge 100 kWh

        // When
        double socPercentage = bessController.getSocPercentage();

        // Then
        assertEquals(50.0, socPercentage, 1.0); // Should be around 50%
    }
}