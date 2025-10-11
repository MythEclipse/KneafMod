package com.kneaf.core.performance.unified;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Simple unit test for PerformanceManager level-specific plugin management.
 */
public class PerformanceManagerSimpleTest {

    @Test
    @DisplayName("Should set monitoring level without errors")
    void testSetMonitoringLevel() {
        // Skip test if PerformanceManager class is not available
        try {
            Class.forName("com.kneaf.core.performance.unified.PerformanceManager");
        } catch (ClassNotFoundException e) {
            assumeTrue(false, "PerformanceManager class not available for testing");
            return;
        }
        
        PerformanceManager manager = PerformanceManager.getInstance();
        
        // Test setting different monitoring levels
        assertDoesNotThrow(() -> {
            manager.setMonitoringLevel(MonitoringLevel.OFF);
            manager.setMonitoringLevel(MonitoringLevel.BASIC);
            manager.setMonitoringLevel(MonitoringLevel.NORMAL);
            manager.setMonitoringLevel(MonitoringLevel.DETAILED);
            manager.setMonitoringLevel(MonitoringLevel.AGGRESSIVE);
        });
        
        // Verify current level is set correctly
        assertEquals(MonitoringLevel.AGGRESSIVE, manager.getCurrentMonitoringLevel());
    }

    @Test
    @DisplayName("Should handle null monitoring level")
    void testNullMonitoringLevel() {
        PerformanceManager manager = PerformanceManager.getInstance();
        
        assertThrows(NullPointerException.class, () -> {
            manager.setMonitoringLevel(null);
        });
    }

    @Test
    @DisplayName("Should not change level when setting same level")
    void testSameLevelSetting() {
        PerformanceManager manager = PerformanceManager.getInstance();
        
        // Set initial level
        manager.setMonitoringLevel(MonitoringLevel.BASIC);
        MonitoringLevel initialLevel = manager.getCurrentMonitoringLevel();
        
        // Set same level again
        manager.setMonitoringLevel(MonitoringLevel.BASIC);
        MonitoringLevel sameLevel = manager.getCurrentMonitoringLevel();
        
        // Should be the same
        assertEquals(initialLevel, sameLevel);
    }

    @Test
    @DisplayName("Should get current monitoring level")
    void testGetCurrentMonitoringLevel() {
        PerformanceManager manager = PerformanceManager.getInstance();
        
        manager.setMonitoringLevel(MonitoringLevel.DETAILED);
        assertEquals(MonitoringLevel.DETAILED, manager.getCurrentMonitoringLevel());
        
        manager.setMonitoringLevel(MonitoringLevel.NORMAL);
        assertEquals(MonitoringLevel.NORMAL, manager.getCurrentMonitoringLevel());
    }

    @Test
    @DisplayName("Should initialize and enable performance monitoring")
    void testInitializeAndEnable() {
        PerformanceManager manager = PerformanceManager.getInstance();
        
        assertDoesNotThrow(() -> {
            manager.initialize();
            manager.enable();
        });
        
        assertTrue(manager.isEnabled());
    }

    @Test
    @DisplayName("Should disable performance monitoring")
    void testDisable() {
        PerformanceManager manager = PerformanceManager.getInstance();
        
        assertDoesNotThrow(() -> {
            manager.initialize();
            manager.enable();
            assertTrue(manager.isEnabled());
            
            manager.disable();
            assertFalse(manager.isEnabled());
        });
    }
}