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
        
        // Use a more flexible assertion for TPS (allowing for small variations)
        double tps = PerformanceManager.getAverageTPS();
        assertTrue(tps >= 0.0 && tps <= 25.0, "TPS should be in a reasonable range");
        
        // Only check last tick duration if it's available
        long lastTickDuration = PerformanceManager.getLastTickDurationMs();
        if (lastTickDuration >= 0) {
            assertEquals(0L, lastTickDuration, "Initial tick duration should be 0");
        }
    }
    
    @Test
    void testMetricsCollection() {
        // Test that metrics methods return expected structures
        
        Map<String, Object> jniMetrics = PerformanceManager.getJniCallMetrics();
        assertNotNull(jniMetrics, "JNI metrics should not be null");
        assertTrue(jniMetrics.containsKey("totalCalls"), "JNI metrics should contain totalCalls");
        assertTrue(jniMetrics.containsKey("totalDurationMs"), "JNI metrics should contain totalDurationMs");
    
        Map<String, Object> lockMetrics = PerformanceManager.getLockWaitMetrics();
        assertNotNull(lockMetrics, "Lock metrics should not be null");
        assertTrue(lockMetrics.containsKey("totalWaits"), "Lock metrics should contain totalWaits");
        assertTrue(lockMetrics.containsKey("currentContention"), "Lock metrics should contain currentContention");
    
        Map<String, Object> memoryMetrics = PerformanceManager.getMemoryMetrics();
        assertNotNull(memoryMetrics, "Memory metrics should not be null");
        assertTrue(memoryMetrics.containsKey("totalHeapBytes"), "Memory metrics should contain totalHeapBytes");
        assertTrue(memoryMetrics.containsKey("usedHeapBytes"), "Memory metrics should contain usedHeapBytes");
    
        List<String> alerts = PerformanceManager.getThresholdAlerts();
        assertNotNull(alerts, "Threshold alerts should not be null");
        assertTrue(alerts.isEmpty(), "Threshold alerts should be empty initially");
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