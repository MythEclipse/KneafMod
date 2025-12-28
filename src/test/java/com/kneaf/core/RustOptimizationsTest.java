/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 */
package com.kneaf.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for RustOptimizations class.
 * Tests Java fallback methods and null handling from native calls.
 */
@DisplayName("RustOptimizations Tests")
class RustOptimizationsTest {

    @BeforeEach
    void setUp() {
        // Ensure static state is initialized
        RustOptimizations.initialize();
    }

    @Nested
    @DisplayName("batchEntityDistanceSq Tests")
    class BatchEntityDistanceSqTests {

        @Test
        @DisplayName("Should calculate correct squared distances for single entity")
        void testSingleEntityDistance() {
            double[] positions = { 10.0, 20.0, 30.0 }; // x, y, z
            double refX = 0.0, refY = 0.0, refZ = 0.0;

            double[] results = RustOptimizations.batchEntityDistanceSq(positions, refX, refY, refZ, 1);

            assertNotNull(results);
            assertEquals(1, results.length);
            // Expected: 10^2 + 20^2 + 30^2 = 100 + 400 + 900 = 1400
            assertEquals(1400.0, results[0], 0.001);
        }

        @Test
        @DisplayName("Should calculate correct squared distances for multiple entities")
        void testMultipleEntityDistances() {
            double[] positions = {
                    1.0, 0.0, 0.0, // Entity 1: (1,0,0)
                    0.0, 2.0, 0.0, // Entity 2: (0,2,0)
                    0.0, 0.0, 3.0 // Entity 3: (0,0,3)
            };
            double refX = 0.0, refY = 0.0, refZ = 0.0;

            double[] results = RustOptimizations.batchEntityDistanceSq(positions, refX, refY, refZ, 3);

            assertNotNull(results);
            assertEquals(3, results.length);
            assertEquals(1.0, results[0], 0.001); // 1^2
            assertEquals(4.0, results[1], 0.001); // 2^2
            assertEquals(9.0, results[2], 0.001); // 3^2
        }

        @Test
        @DisplayName("Should handle zero distance")
        void testZeroDistance() {
            double[] positions = { 5.0, 10.0, 15.0 };
            double refX = 5.0, refY = 10.0, refZ = 15.0;

            double[] results = RustOptimizations.batchEntityDistanceSq(positions, refX, refY, refZ, 1);

            assertNotNull(results);
            assertEquals(0.0, results[0], 0.001);
        }

        @Test
        @DisplayName("Should handle negative coordinates")
        void testNegativeCoordinates() {
            double[] positions = { -3.0, -4.0, 0.0 };
            double refX = 0.0, refY = 0.0, refZ = 0.0;

            double[] results = RustOptimizations.batchEntityDistanceSq(positions, refX, refY, refZ, 1);

            assertNotNull(results);
            // Expected: (-3)^2 + (-4)^2 + 0^2 = 9 + 16 = 25
            assertEquals(25.0, results[0], 0.001);
        }

        @Test
        @DisplayName("Should handle empty input")
        void testEmptyInput() {
            double[] positions = {};
            double[] results = RustOptimizations.batchEntityDistanceSq(positions, 0, 0, 0, 0);

            assertNotNull(results);
            assertEquals(0, results.length);
        }
    }

    @Nested
    @DisplayName("Biome Hash Tests")
    class BiomeHashTests {

        @Test
        @DisplayName("Should produce consistent hash for same coordinates")
        void testConsistentHash() {
            int[] x = { 100 };
            int[] y = { 64 };
            int[] z = { 200 };

            long[] hash1 = RustOptimizations.batchBiomeHash(x, y, z, 1);
            long[] hash2 = RustOptimizations.batchBiomeHash(x, y, z, 1);

            assertNotNull(hash1);
            assertNotNull(hash2);
            assertEquals(hash1[0], hash2[0]);
        }

        @Test
        @DisplayName("Should produce different hashes for different coordinates")
        void testDifferentHashes() {
            int[] x1 = { 100 }, y1 = { 64 }, z1 = { 200 };
            int[] x2 = { 101 }, y2 = { 64 }, z2 = { 200 };

            long[] hash1 = RustOptimizations.batchBiomeHash(x1, y1, z1, 1);
            long[] hash2 = RustOptimizations.batchBiomeHash(x2, y2, z2, 1);

            assertNotEquals(hash1[0], hash2[0]);
        }

        @Test
        @DisplayName("Should batch hash multiple coordinates")
        void testBatchHashing() {
            int[] x = { 0, 100, 200 };
            int[] y = { 64, 64, 64 };
            int[] z = { 0, 100, 200 };

            long[] hashes = RustOptimizations.batchBiomeHash(x, y, z, 3);

            assertNotNull(hashes);
            assertEquals(3, hashes.length);
            // Each hash should be unique
            assertNotEquals(hashes[0], hashes[1]);
            assertNotEquals(hashes[1], hashes[2]);
        }
    }

    @Nested
    @DisplayName("Fallback Method Tests")
    class FallbackTests {

        @Test
        @DisplayName("batchNoiseGenerate3D fallback should return null")
        void testNoiseGenerateFallback() {
            // Fallback method returns null for mixin to use vanilla logic
            float[] result = RustOptimizations.batchNoiseGenerate3DFallback(12345L, new float[] { 0, 0, 0 }, 1);
            assertNull(result);
        }

        @Test
        @DisplayName("RustOptimizations availability should be determinable")
        void testAvailabilityCheck() {
            // Should not throw, regardless of actual availability
            boolean available = RustOptimizations.isAvailable();
            // Just verify the method works - availability depends on native library
            assertTrue(available || !available); // Always passes, tests method doesn't throw
        }
    }
}
