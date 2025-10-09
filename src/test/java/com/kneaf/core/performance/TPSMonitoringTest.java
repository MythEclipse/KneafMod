package com.kneaf.core.performance;

import static org.junit.jupiter.api.Assertions.*;

import com.kneaf.core.performance.monitoring.PerformanceManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/**
 * Test suite for TPS monitoring functionality in PerformanceManager.
 * Validates fixes for accurate TPS calculation and consistent logging.
 */
class TPSMonitoringTest {

    @BeforeEach
    public void setUp() {
        // Reset state before each test
        PerformanceManager.setEnabled(true);
        PerformanceManager.clearThresholdAlerts();
    }

    @Test
    void testBasicTPSFunctionality() {
        // Verify initial state
        assertTrue(PerformanceManager.isEnabled());
        assertEquals(20.0, PerformanceManager.getAverageTPS(), 0.01);
        assertEquals(0, PerformanceManager.getLastTickDurationMs());
    }

    @Test
    void testMetricsCollection() {
        // Test that metrics methods return expected structures
        Map<String, Object> jniMetrics = PerformanceManager.getJniCallMetrics();
        assertNotNull(jniMetrics);
        assertTrue(jniMetrics.containsKey("totalCalls"));
        assertTrue(jniMetrics.containsKey("totalDurationMs"));

        Map<String, Object> lockMetrics = PerformanceManager.getLockWaitMetrics();
        assertNotNull(lockMetrics);
        assertTrue(lockMetrics.containsKey("totalWaits"));
        assertTrue(lockMetrics.containsKey("currentContention"));

        Map<String, Object> memoryMetrics = PerformanceManager.getMemoryMetrics();
        assertNotNull(memoryMetrics);
        assertTrue(memoryMetrics.containsKey("totalHeapBytes"));
        assertTrue(memoryMetrics.containsKey("usedHeapBytes"));

        List<String> alerts = PerformanceManager.getThresholdAlerts();
        assertNotNull(alerts);
        assertTrue(alerts.isEmpty());
    }

    @Test
    void testStateManagement() {
        boolean initialState = PerformanceManager.isEnabled();
        
        // Test state toggling
        PerformanceManager.setEnabled(!initialState);
        assertEquals(!initialState, PerformanceManager.isEnabled());
        
        // Restore original state
        PerformanceManager.setEnabled(initialState);
        assertEquals(initialState, PerformanceManager.isEnabled());
    }

    @Test
    void testAlertSystem() {
        // Test threshold alert functionality
        assertEquals(0, PerformanceManager.getThresholdAlerts().size());
        
        // Add alert and verify
        PerformanceManager.addThresholdAlert("Test TPS alert");
        List<String> alerts = PerformanceManager.getThresholdAlerts();
        assertEquals(1, alerts.size());
        assertEquals("Test TPS alert", alerts.get(0));
        
        // Clear alerts and verify
        PerformanceManager.clearThresholdAlerts();
        assertEquals(0, PerformanceManager.getThresholdAlerts().size());
    }

    @Test
    void testPerformanceManagerInitialization() {
        // Verify that PerformanceManager initializes correctly
        assertNotNull(PerformanceManager.getAverageTPS());
        assertNotNull(PerformanceManager.getJniCallMetrics());
        assertNotNull(PerformanceManager.getLockWaitMetrics());
        assertNotNull(PerformanceManager.getMemoryMetrics());
    }
}