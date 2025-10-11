package com.kneaf.core.logging;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Test class for logging integration between Rust and Java.
 * These tests are skipped if native logging is not available.
 */
public class LoggingIntegrationTest {
    
    @BeforeEach
    public void setUp() {
        // Skip test if RustLogger class is not available
        try {
            Class.forName("com.kneaf.core.logging.RustLogger");
        } catch (ClassNotFoundException e) {
            assumeTrue(false, "RustLogger class not available for testing");
        }
    }
    
    @Test
    public void testLogFromRust() {
        // Test basic logging functionality - only run if native logging is available
        assertDoesNotThrow(() -> {
            RustLogger.logFromRust("INFO", "Test message from Rust");
            RustLogger.logFromRust("DEBUG", "Debug message from Rust");
            RustLogger.logFromRust("WARN", "Warning message from Rust");
            RustLogger.logFromRust("ERROR", "Error message from Rust");
        });
    }
    
    @Test
    public void testSystemStatusLogging() {
        // Test system status logging
        assertDoesNotThrow(() -> {
            RustLogger.logSystemStatus(
                "AVX2 ✓ AVX-512 ✓ SSE4.2 ✓", 
                "AVX-512 Extreme", 
                0.05,  // 5% fallback rate
                2.5    // 2.5 ops/cycle
            );
        });
    }
    
    @Test
    public void testMemoryPoolStatusLogging() {
        // Test memory pool status logging
        assertDoesNotThrow(() -> {
            RustLogger.logMemoryPoolStatus(85.5, 92.0, 3);
        });
    }
    
    @Test
    public void testThreadPoolStatusLogging() {
        // Test thread pool status logging
        assertDoesNotThrow(() -> {
            RustLogger.logThreadPoolStatus(4, 2, 75.5);
        });
    }
    
    @Test
    public void testPerformanceMetricsLogging() {
        // Test performance metrics logging
        assertDoesNotThrow(() -> {
            RustLogger.logPerformanceMetrics(19.8, 45.2, 15L);
        });
    }
    
    @Test
    public void testConfigurationStatusLogging() {
        // Test configuration status logging
        assertDoesNotThrow(() -> {
            RustLogger.logConfigurationStatus(true, true, 17.0);
        });
    }
    
    @Test
    public void testStartupInfoLogging() {
        // Test startup information logging
        assertDoesNotThrow(() -> {
            RustLogger.logStartupInfo(
                "Dynamic entity ticking, Item stack merging, Mob AI optimization",
                "Intel Core i9-13900K with AVX-512 support",
                "Extreme performance mode enabled"
            );
        });
    }
    
    @Test
    public void testRealTimeStatusLogging() {
        // Test real-time status logging
        assertDoesNotThrow(() -> {
            RustLogger.logRealTimeStatus(
                "TPS: 19.8, Tick: 45ms, Memory: 85.5%, GC: 15, Locks: 3",
                "HIGH_MEMORY_USAGE"
            );
            
            RustLogger.logRealTimeStatus(
                "TPS: 20.0, Tick: 40ms, Memory: 75.0%, GC: 10, Locks: 1",
                ""
            );
        });
    }
    
    @Test
    public void testThresholdEventLogging() {
        // Test threshold event logging
        assertDoesNotThrow(() -> {
            RustLogger.logThresholdEvent(
                "MEMORY_USAGE",
                "Memory usage exceeded threshold",
                90.0,
                95.5
            );
            
            RustLogger.logThresholdEvent(
                "JNI_CALL",
                "JNI call duration exceeded threshold",
                100.0,
                150.0
            );
        });
    }
    
    @Test
    public void testLogLevels() {
        // Test all log levels
        assertDoesNotThrow(() -> {
            // Test different log levels
            String testMessage = "Test message for different log levels";
            
            RustLogger.logFromRust("DEBUG", testMessage + " - DEBUG");
            RustLogger.logFromRust("INFO", testMessage + " - INFO");
            RustLogger.logFromRust("WARN", testMessage + " - WARN");
            RustLogger.logFromRust("ERROR", testMessage + " - ERROR");
            RustLogger.logFromRust("TRACE", testMessage + " - TRACE");
            RustLogger.logFromRust("UNKNOWN", testMessage + " - UNKNOWN"); // Should default to INFO
        });
    }
    
    @Test
    public void testNullSafety() {
        // Test null safety in logging methods
        assertDoesNotThrow(() -> {
            // Should handle null values gracefully
            RustLogger.logFromRust(null, null);
            RustLogger.logFromRust("INFO", null);
            RustLogger.logFromRust(null, "Test message");
            
            RustLogger.logRealTimeStatus(null, null);
            RustLogger.logRealTimeStatus("Status", null);
            RustLogger.logRealTimeStatus(null, "Events");
        });
    }
}