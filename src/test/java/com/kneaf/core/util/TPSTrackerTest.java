/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 */
package com.kneaf.core.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for TPSTracker utility class.
 * 
 * <p>
 * TPSTracker is fed real tick data from Minecraft's server tick loop via:
 * - DedicatedServerMixin.tickServer() - measures actual server tick duration
 * - ServerLevelMixin.tick() - measures level tick duration
 * 
 * <p>
 * Minecraft targets 20 TPS (ticks per second), meaning each tick should
 * complete in 50ms (1000ms / 20 ticks = 50ms/tick).
 */
@DisplayName("TPSTracker Tests (Minecraft Integration)")
class TPSTrackerTest {

    // Minecraft's target tick time in milliseconds (20 TPS)
    private static final long MINECRAFT_TARGET_TICK_MS = 50L;

    // One complete measurement cycle (TPSTracker calculates TPS every 20 ticks)
    private static final int TICKS_PER_CYCLE = 20;

    @BeforeEach
    void setUp() {
        // Reset state by recording a full cycle of ideal ticks
        // This simulates a server running at perfect 20 TPS
        for (int i = 0; i < TICKS_PER_CYCLE * 2; i++) {
            TPSTracker.recordTick(MINECRAFT_TARGET_TICK_MS);
        }
    }

    @Nested
    @DisplayName("getCurrentTPS Tests")
    class GetCurrentTPSTests {

        @Test
        @DisplayName("Should return 20 TPS when server ticks at ideal 50ms rate")
        void testPerfectTPS() {
            // Simulate server running perfectly - each tick takes exactly 50ms
            // This is Minecraft's target tick rate
            for (int i = 0; i < TICKS_PER_CYCLE; i++) {
                TPSTracker.recordTick(MINECRAFT_TARGET_TICK_MS);
            }

            double tps = TPSTracker.getCurrentTPS();
            assertEquals(20.0, tps, 0.1);
        }

        @Test
        @DisplayName("Should return ~10 TPS when server ticks take 100ms (lag)")
        void testSlowTicks() {
            // Simulate server lag - each tick takes 100ms (double the target)
            // This happens when there are too many entities or complex operations
            for (int i = 0; i < TICKS_PER_CYCLE; i++) {
                TPSTracker.recordTick(100L); // 2x target = half TPS
            }

            double tps = TPSTracker.getCurrentTPS();
            // Allow tolerance due to rolling average with previous cycle
            assertEquals(10.0, tps, 1.0);
        }

        @Test
        @DisplayName("Should cap TPS at 20 even when ticks are faster than 50ms")
        void testTPSCap() {
            // Simulate server running faster than needed (ticks complete in 25ms)
            // Server should still be capped at 20 TPS max
            for (int i = 0; i < TICKS_PER_CYCLE; i++) {
                TPSTracker.recordTick(25L); // Faster than 50ms target
            }

            double tps = TPSTracker.getCurrentTPS();
            assertTrue(tps <= 20.0, "TPS should be capped at Minecraft's max of 20");
        }

        @Test
        @DisplayName("Should return ~5 TPS when server severely lags at 200ms/tick")
        void testVerySlowTicks() {
            // Simulate severe server lag - common with large modpacks or entity farms
            // Each tick takes 200ms (4x target)
            for (int i = 0; i < TICKS_PER_CYCLE; i++) {
                TPSTracker.recordTick(200L); // 4x target = 1/4 TPS
            }

            double tps = TPSTracker.getCurrentTPS();
            // Allow tolerance due to rolling average calculation
            assertEquals(5.0, tps, 1.0);
        }
    }

    @Nested
    @DisplayName("recordTick Tests")
    class RecordTickTests {

        @Test
        @DisplayName("Should accept tick duration from System.nanoTime() measurement")
        void testRecordTickLong() {
            // DedicatedServerMixin passes tick time in ms from nanoTime delta
            assertDoesNotThrow(() -> TPSTracker.recordTick(MINECRAFT_TARGET_TICK_MS));
        }

        @Test
        @DisplayName("Should accept double precision tick duration")
        void testRecordTickDouble() {
            // Some calculations may produce fractional milliseconds
            assertDoesNotThrow(() -> TPSTracker.recordTick(50.5));
        }

        @Test
        @DisplayName("Should handle zero tick time gracefully (instant tick)")
        void testZeroTickTime() {
            // Edge case: tick completes instantly (extremely unlikely but possible)
            for (int i = 0; i < TICKS_PER_CYCLE; i++) {
                TPSTracker.recordTick(0L);
            }

            double tps = TPSTracker.getCurrentTPS();
            // Zero tick time means infinite TPS, but capped at 20
            assertEquals(20.0, tps, 0.1);
        }
    }

    @Nested
    @DisplayName("getStatistics Tests")
    class GetStatisticsTests {

        @Test
        @DisplayName("Should return formatted statistics string")
        void testStatisticsFormat() {
            String stats = TPSTracker.getStatistics();

            assertNotNull(stats);
            assertTrue(stats.startsWith("TPSTracker{tps="));
            assertTrue(stats.endsWith("}"));
        }

        @Test
        @DisplayName("Should include current TPS in statistics")
        void testStatisticsContainsTPS() {
            // Set known TPS (20)
            for (int i = 0; i < TICKS_PER_CYCLE; i++) {
                TPSTracker.recordTick(MINECRAFT_TARGET_TICK_MS);
            }

            String stats = TPSTracker.getStatistics();
            assertTrue(stats.contains("20.0") || stats.contains("20,0"));
        }
    }
}
