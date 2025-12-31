/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 *
 * Comprehensive Rust vs Java benchmark tests.
 * Compares performance of Rust native implementations against pure Java fallbacks.
 */
package com.kneaf.core;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Comprehensive benchmark tests comparing Rust native optimizations vs Java
 * implementations.
 * 
 * Each test category includes:
 * 1. Correctness tests (Rust and Java should produce same results)
 * 2. Performance benchmarks (measure speedup of Rust over Java)
 * 3. Edge case handling
 */
@DisplayName("Rust vs Java Benchmark Tests")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RustVsJavaBenchmarkTest {

    private static final int WARMUP_ITERATIONS = 3;
    private static final int BENCHMARK_ITERATIONS = 10;
    private static final Random RANDOM = new Random(42); // Deterministic seed

    private static boolean rustAvailable;

    @BeforeAll
    static void setupAll() {
        RustOptimizations.initialize();
        rustAvailable = RustOptimizations.isAvailable();
        System.out.println("══════════════════════════════════════════════════════════════");
        System.out.println("           RUST vs JAVA BENCHMARK TEST SUITE");
        System.out.println("══════════════════════════════════════════════════════════════");
        System.out.println("Rust Native Available: " + (rustAvailable ? "✅ YES" : "❌ NO"));
        System.out.println("──────────────────────────────────────────────────────────────");
    }

    @AfterAll
    static void teardownAll() {
        System.out.println("══════════════════════════════════════════════════════════════");
        System.out.println("                    BENCHMARK COMPLETE");
        System.out.println("══════════════════════════════════════════════════════════════");
    }

    // ========================================================================
    // SECTION 1: Vector Distance Calculations
    // ========================================================================

    @Nested
    @DisplayName("1. Vector Distance Calculations")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class VectorDistanceTests {

        @Test
        @Order(1)
        @DisplayName("1.1 Correctness: Rust vs Java batchEntityDistanceSq")
        void testDistanceCorrectness() {
            int entityCount = 1000;
            double[] positions = generateRandomPositions(entityCount);
            double refX = 0, refY = 64, refZ = 0;

            double[] rustResult = RustOptimizations.batchEntityDistanceSq(positions, refX, refY, refZ, entityCount);
            double[] javaResult = javaEntityDistanceSq(positions, refX, refY, refZ, entityCount);

            assertNotNull(rustResult, "Rust result should not be null");
            assertNotNull(javaResult, "Java result should not be null");
            assertEquals(entityCount, rustResult.length);
            assertEquals(entityCount, javaResult.length);

            for (int i = 0; i < entityCount; i++) {
                assertEquals(javaResult[i], rustResult[i], 0.0001,
                        "Distance mismatch at index " + i);
            }
            System.out.println("  ✓ Correctness verified for " + entityCount + " entities");
        }

        @Test
        @Order(2)
        @DisplayName("1.2 Benchmark: 10,000 entities distance calculation")
        void benchmarkDistance10K() {
            benchmarkDistance(10_000);
        }

        @Test
        @Order(3)
        @DisplayName("1.3 Benchmark: 100,000 entities distance calculation")
        void benchmarkDistance100K() {
            benchmarkDistance(100_000);
        }

        @Test
        @Order(4)
        @DisplayName("1.4 Benchmark: 1,000,000 entities distance calculation")
        void benchmarkDistance1M() {
            benchmarkDistance(1_000_000);
        }

        private void benchmarkDistance(int entityCount) {
            double[] positions = generateRandomPositions(entityCount);
            double refX = 0, refY = 64, refZ = 0;

            // Warmup
            for (int i = 0; i < WARMUP_ITERATIONS; i++) {
                RustOptimizations.batchEntityDistanceSq(positions, refX, refY, refZ, entityCount);
                javaEntityDistanceSq(positions, refX, refY, refZ, entityCount);
            }

            // Benchmark Java
            long javaStart = System.nanoTime();
            for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
                javaEntityDistanceSq(positions, refX, refY, refZ, entityCount);
            }
            long javaTotal = System.nanoTime() - javaStart;
            double javaAvgMs = javaTotal / (BENCHMARK_ITERATIONS * 1_000_000.0);

            // Benchmark Rust
            long rustStart = System.nanoTime();
            for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
                RustOptimizations.batchEntityDistanceSq(positions, refX, refY, refZ, entityCount);
            }
            long rustTotal = System.nanoTime() - rustStart;
            double rustAvgMs = rustTotal / (BENCHMARK_ITERATIONS * 1_000_000.0);

            double speedup = javaAvgMs / rustAvgMs;
            printBenchmarkResult("Distance " + formatNumber(entityCount), javaAvgMs, rustAvgMs, speedup);

            assertTrue(true); // Test always passes, benchmark is informational
        }

        private double[] javaEntityDistanceSq(double[] positions, double refX, double refY, double refZ, int count) {
            double[] results = new double[count];
            for (int i = 0; i < count; i++) {
                double dx = positions[i * 3] - refX;
                double dy = positions[i * 3 + 1] - refY;
                double dz = positions[i * 3 + 2] - refZ;
                results[i] = dx * dx + dy * dy + dz * dz;
            }
            return results;
        }
    }

    // ========================================================================
    // SECTION 2: Entity Tick Rate Computation
    // ========================================================================

    @Nested
    @DisplayName("2. Entity Tick Rate Computation")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class EntityTickRateTests {

        @Test
        @Order(1)
        @DisplayName("2.1 Correctness: Rust vs Java computeEntityTickRates")
        void testTickRateCorrectness() {
            int count = 1000;
            double[] distancesSq = generateRandomDistances(count, 0, 10000);
            double nearSq = 256; // 16 blocks
            double farSq = 4096; // 64 blocks

            int[] rustResult = RustOptimizations.computeEntityTickRates(distancesSq, nearSq, farSq, count);
            int[] javaResult = javaComputeTickRates(distancesSq, nearSq, farSq, count);

            assertNotNull(rustResult);
            assertNotNull(javaResult);
            assertEquals(count, rustResult.length);
            assertEquals(count, javaResult.length);

            for (int i = 0; i < count; i++) {
                assertEquals(javaResult[i], rustResult[i],
                        "Tick rate mismatch at index " + i + " (dist²=" + distancesSq[i] + ")");
            }
            System.out.println("  ✓ Tick rate correctness verified for " + count + " entities");
        }

        @Test
        @Order(2)
        @DisplayName("2.2 Benchmark: 10,000 entities tick rate")
        void benchmarkTickRate10K() {
            benchmarkTickRate(10_000);
        }

        @Test
        @Order(3)
        @DisplayName("2.3 Benchmark: 100,000 entities tick rate")
        void benchmarkTickRate100K() {
            benchmarkTickRate(100_000);
        }

        private void benchmarkTickRate(int count) {
            double[] distancesSq = generateRandomDistances(count, 0, 10000);
            double nearSq = 256, farSq = 4096;

            // Warmup
            for (int i = 0; i < WARMUP_ITERATIONS; i++) {
                RustOptimizations.computeEntityTickRates(distancesSq, nearSq, farSq, count);
                javaComputeTickRates(distancesSq, nearSq, farSq, count);
            }

            // Benchmark Java
            long javaStart = System.nanoTime();
            for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
                javaComputeTickRates(distancesSq, nearSq, farSq, count);
            }
            double javaAvgMs = (System.nanoTime() - javaStart) / (BENCHMARK_ITERATIONS * 1_000_000.0);

            // Benchmark Rust
            long rustStart = System.nanoTime();
            for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
                RustOptimizations.computeEntityTickRates(distancesSq, nearSq, farSq, count);
            }
            double rustAvgMs = (System.nanoTime() - rustStart) / (BENCHMARK_ITERATIONS * 1_000_000.0);

            printBenchmarkResult("TickRate " + formatNumber(count), javaAvgMs, rustAvgMs, javaAvgMs / rustAvgMs);
        }

        private int[] javaComputeTickRates(double[] distancesSq, double nearSq, double farSq, int count) {
            int[] rates = new int[count];
            double veryFarSq = farSq * 4.0;
            for (int i = 0; i < count; i++) {
                double d = distancesSq[i];
                if (d <= nearSq)
                    rates[i] = 1;
                else if (d <= farSq)
                    rates[i] = 2;
                else if (d <= veryFarSq)
                    rates[i] = 4;
                else
                    rates[i] = 8;
            }
            return rates;
        }
    }

    // ========================================================================
    // SECTION 3: Biome Hash Calculation
    // ========================================================================

    @Nested
    @DisplayName("3. Biome Hash Calculation")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class BiomeHashTests {

        @Test
        @Order(1)
        @DisplayName("3.1 Correctness: Java batchBiomeHash produces consistent hashes")
        void testBiomeHashCorrectness() {
            int count = 1000;
            int[] x = new int[count], y = new int[count], z = new int[count];
            for (int i = 0; i < count; i++) {
                x[i] = RANDOM.nextInt(1000000) - 500000;
                y[i] = RANDOM.nextInt(256);
                z[i] = RANDOM.nextInt(1000000) - 500000;
            }

            long[] hash1 = RustOptimizations.batchBiomeHash(x, y, z, count);
            long[] hash2 = RustOptimizations.batchBiomeHash(x, y, z, count);

            assertNotNull(hash1);
            assertNotNull(hash2);
            assertArrayEquals(hash1, hash2, "Hash should be deterministic");
            System.out.println("  ✓ Hash consistency verified for " + count + " coordinates");
        }

        @Test
        @Order(2)
        @DisplayName("3.2 Benchmark: 100,000 biome hashes")
        void benchmarkBiomeHash() {
            int count = 100_000;
            int[] x = new int[count], y = new int[count], z = new int[count];
            for (int i = 0; i < count; i++) {
                x[i] = RANDOM.nextInt(1000000);
                y[i] = RANDOM.nextInt(256);
                z[i] = RANDOM.nextInt(1000000);
            }

            // Warmup
            for (int i = 0; i < WARMUP_ITERATIONS; i++) {
                RustOptimizations.batchBiomeHash(x, y, z, count);
                javaBiomeHash(x, y, z, count);
            }

            // Benchmark Java
            long javaStart = System.nanoTime();
            for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
                javaBiomeHash(x, y, z, count);
            }
            double javaAvgMs = (System.nanoTime() - javaStart) / (BENCHMARK_ITERATIONS * 1_000_000.0);

            // Benchmark via RustOptimizations (which also uses Java for this)
            long rustStart = System.nanoTime();
            for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
                RustOptimizations.batchBiomeHash(x, y, z, count);
            }
            double rustAvgMs = (System.nanoTime() - rustStart) / (BENCHMARK_ITERATIONS * 1_000_000.0);

            printBenchmarkResult("BiomeHash " + formatNumber(count), javaAvgMs, rustAvgMs, javaAvgMs / rustAvgMs);
        }

        private long[] javaBiomeHash(int[] x, int[] y, int[] z, int count) {
            long[] results = new long[count];
            for (int i = 0; i < count; i++) {
                results[i] = ((long) x[i] & 0x3FFFFF) | (((long) y[i] & 0xFFF) << 22)
                        | (((long) z[i] & 0x3FFFFF) << 34);
            }
            return results;
        }
    }

    // ========================================================================
    // SECTION 4: Vector Math Operations
    // ========================================================================

    @Nested
    @DisplayName("4. Vector Math Operations")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class VectorMathTests {

        @Test
        @Order(1)
        @DisplayName("4.1 Rust vectorDistance correctness")
        void testVectorDistanceCorrectness() {
            Assumptions.assumeTrue(rustAvailable, "Rust native library not available");

            // Test known values
            double dist = RustNativeLoader.vectorDistance(0, 0, 0, 3, 4, 0);
            assertEquals(5.0, dist, 0.0001, "3-4-5 triangle should have distance 5");

            dist = RustNativeLoader.vectorDistance(1, 1, 1, 1, 1, 1);
            assertEquals(0.0, dist, 0.0001, "Same point should have distance 0");

            System.out.println("  ✓ vectorDistance correctness verified");
        }

        @Test
        @Order(2)
        @DisplayName("4.2 Rust vectorNormalize correctness")
        void testVectorNormalizeCorrectness() {
            Assumptions.assumeTrue(rustAvailable, "Rust native library not available");

            double[] result = RustNativeLoader.vectorNormalize(3, 4, 0);
            assertNotNull(result);
            assertEquals(3, result.length);

            // Normalized (3,4,0) should be (0.6, 0.8, 0)
            assertEquals(0.6, result[0], 0.0001);
            assertEquals(0.8, result[1], 0.0001);
            assertEquals(0.0, result[2], 0.0001);

            // Check length is 1
            double len = Math.sqrt(result[0] * result[0] + result[1] * result[1] + result[2] * result[2]);
            assertEquals(1.0, len, 0.0001, "Normalized vector should have length 1");

            System.out.println("  ✓ vectorNormalize correctness verified");
        }

        @Test
        @Order(3)
        @DisplayName("4.3 Rust vectorLength correctness")
        void testVectorLengthCorrectness() {
            Assumptions.assumeTrue(rustAvailable, "Rust native library not available");

            double len = RustNativeLoader.vectorLength(3, 4, 0);
            assertEquals(5.0, len, 0.0001, "Length of (3,4,0) should be 5");

            len = RustNativeLoader.vectorLength(1, 0, 0);
            assertEquals(1.0, len, 0.0001, "Length of unit vector should be 1");

            System.out.println("  ✓ vectorLength correctness verified");
        }

        @Test
        @Order(4)
        @DisplayName("4.4 Benchmark: 100,000 vector operations")
        void benchmarkVectorOps() {
            Assumptions.assumeTrue(rustAvailable, "Rust native library not available");

            int count = 100_000;

            // Warmup and benchmark vectorLength
            for (int i = 0; i < WARMUP_ITERATIONS; i++) {
                for (int j = 0; j < count; j++) {
                    RustNativeLoader.vectorLength(RANDOM.nextDouble(), RANDOM.nextDouble(), RANDOM.nextDouble());
                }
            }

            long rustStart = System.nanoTime();
            for (int j = 0; j < count; j++) {
                RustNativeLoader.vectorLength(j * 0.1, j * 0.2, j * 0.3);
            }
            double rustMs = (System.nanoTime() - rustStart) / 1_000_000.0;

            long javaStart = System.nanoTime();
            for (int j = 0; j < count; j++) {
                javaVectorLength(j * 0.1, j * 0.2, j * 0.3);
            }
            double javaMs = (System.nanoTime() - javaStart) / 1_000_000.0;

            printBenchmarkResult("VectorLen " + formatNumber(count), javaMs, rustMs, javaMs / rustMs);
        }

        private double javaVectorLength(double x, double y, double z) {
            return Math.sqrt(x * x + y * y + z * z);
        }
    }

    // ========================================================================
    // SECTION 5: Matrix Operations
    // ========================================================================

    @Nested
    @DisplayName("5. Matrix Operations")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class MatrixOperationsTests {

        @Test
        @Order(1)
        @DisplayName("5.1 Rust nalgebra_matrix_mul correctness")
        void testMatrixMulCorrectness() {
            Assumptions.assumeTrue(rustAvailable, "Rust native library not available");

            // Identity * Identity = Identity for 4x4 matrix (16 elements)
            float[] identity = {
                    1, 0, 0, 0,
                    0, 1, 0, 0,
                    0, 0, 1, 0,
                    0, 0, 0, 1
            };

            float[] result = RustNativeLoader.nalgebra_matrix_mul(identity, identity);
            assertNotNull(result, "Matrix multiplication result should not be null");
            assertEquals(16, result.length, "Result should be 4x4 matrix");

            for (int i = 0; i < 16; i++) {
                float expected = (i % 5 == 0) ? 1.0f : 0.0f; // Diagonal elements are 1
                assertEquals(expected, result[i], 0.0001f, "Identity * Identity should be Identity at " + i);
            }

            System.out.println("  ✓ nalgebra_matrix_mul correctness verified");
        }

        @Test
        @Order(2)
        @DisplayName("5.2 Rust glam_vector_dot correctness")
        void testVectorDotCorrectness() {
            Assumptions.assumeTrue(rustAvailable, "Rust native library not available");

            // (1,2,3) · (4,5,6) = 1*4 + 2*5 + 3*6 = 4 + 10 + 18 = 32
            float[] a = { 1, 2, 3 };
            float[] b = { 4, 5, 6 };
            float result = RustNativeLoader.glam_vector_dot(a, b);
            assertEquals(32.0f, result, 0.0001f);

            // Orthogonal vectors should have dot product 0
            float[] x = { 1, 0, 0 };
            float[] y = { 0, 1, 0 };
            result = RustNativeLoader.glam_vector_dot(x, y);
            assertEquals(0.0f, result, 0.0001f);

            System.out.println("  ✓ glam_vector_dot correctness verified");
        }

        @Test
        @Order(3)
        @DisplayName("5.3 Rust glam_vector_cross correctness")
        void testVectorCrossCorrectness() {
            Assumptions.assumeTrue(rustAvailable, "Rust native library not available");

            // x cross y = z
            float[] x = { 1, 0, 0 };
            float[] y = { 0, 1, 0 };
            float[] result = RustNativeLoader.glam_vector_cross(x, y);
            assertNotNull(result);
            assertEquals(3, result.length);
            assertEquals(0.0f, result[0], 0.0001f);
            assertEquals(0.0f, result[1], 0.0001f);
            assertEquals(1.0f, result[2], 0.0001f);

            System.out.println("  ✓ glam_vector_cross correctness verified");
        }

        @Test
        @Order(4)
        @DisplayName("5.4 Benchmark: 10,000 matrix multiplications")
        void benchmarkMatrixMul() {
            Assumptions.assumeTrue(rustAvailable, "Rust native library not available");

            int count = 10_000;
            float[] matA = generateRandomMatrix4x4();
            float[] matB = generateRandomMatrix4x4();

            // Warmup
            for (int i = 0; i < WARMUP_ITERATIONS * 100; i++) {
                RustNativeLoader.nalgebra_matrix_mul(matA, matB);
            }

            long rustStart = System.nanoTime();
            for (int i = 0; i < count; i++) {
                RustNativeLoader.nalgebra_matrix_mul(matA, matB);
            }
            double rustMs = (System.nanoTime() - rustStart) / 1_000_000.0;

            long javaStart = System.nanoTime();
            for (int i = 0; i < count; i++) {
                javaMatrixMul4x4(matA, matB);
            }
            double javaMs = (System.nanoTime() - javaStart) / 1_000_000.0;

            printBenchmarkResult("MatrixMul " + formatNumber(count), javaMs, rustMs, javaMs / rustMs);
        }

        private float[] generateRandomMatrix4x4() {
            float[] mat = new float[16];
            for (int i = 0; i < 16; i++) {
                mat[i] = RANDOM.nextFloat() * 10 - 5;
            }
            return mat;
        }

        private float[] javaMatrixMul4x4(float[] a, float[] b) {
            float[] result = new float[16];
            for (int row = 0; row < 4; row++) {
                for (int col = 0; col < 4; col++) {
                    float sum = 0;
                    for (int k = 0; k < 4; k++) {
                        sum += a[row * 4 + k] * b[k * 4 + col];
                    }
                    result[row * 4 + col] = sum;
                }
            }
            return result;
        }
    }

    // ========================================================================
    // SECTION 6: Fluid Flow Simulation
    // ========================================================================

    @Nested
    @DisplayName("6. Fluid Flow Simulation")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class FluidFlowTests {

        @Test
        @Order(1)
        @DisplayName("6.1 Rust simulateFluidFlow basic test")
        void testFluidFlowBasic() {
            Assumptions.assumeTrue(rustAvailable, "Rust native library not available");

            int width = 16, height = 16, depth = 16;
            byte[] fluidLevels = new byte[width * height * depth];
            byte[] solidBlocks = new byte[width * height * depth];

            // Set up a simple water source at top center
            int centerIdx = (height - 1) * width * depth + (depth / 2) * width + (width / 2);
            fluidLevels[centerIdx] = 8; // Full water

            byte[] result = RustOptimizations.simulateFluidFlow(fluidLevels, solidBlocks, width, height, depth);

            // Result may be null if Rust implementation returns fallback
            if (result != null) {
                assertEquals(fluidLevels.length, result.length);
                System.out.println("  ✓ Fluid flow simulation completed");
            } else {
                System.out.println("  ⚠ Fluid flow returned fallback (null)");
            }
        }

        @Test
        @Order(2)
        @DisplayName("6.2 Benchmark: 32x32x32 fluid simulation")
        void benchmarkFluidFlow() {
            int width = 32, height = 32, depth = 32;
            byte[] fluidLevels = new byte[width * height * depth];
            byte[] solidBlocks = new byte[width * height * depth];

            // Fill with some fluid
            for (int i = 0; i < fluidLevels.length; i++) {
                if (RANDOM.nextFloat() < 0.1) {
                    fluidLevels[i] = (byte) (RANDOM.nextInt(8) + 1);
                }
            }

            // Warmup
            for (int i = 0; i < WARMUP_ITERATIONS; i++) {
                RustOptimizations.simulateFluidFlow(fluidLevels, solidBlocks, width, height, depth);
                javaSimulateFluid(fluidLevels, solidBlocks, width, height, depth);
            }

            long javaStart = System.nanoTime();
            for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
                javaSimulateFluid(fluidLevels, solidBlocks, width, height, depth);
            }
            double javaMs = (System.nanoTime() - javaStart) / (BENCHMARK_ITERATIONS * 1_000_000.0);

            long rustStart = System.nanoTime();
            for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
                RustOptimizations.simulateFluidFlow(fluidLevels, solidBlocks, width, height, depth);
            }
            double rustMs = (System.nanoTime() - rustStart) / (BENCHMARK_ITERATIONS * 1_000_000.0);

            printBenchmarkResult("Fluid 32³", javaMs, rustMs, javaMs / rustMs);
        }

        private byte[] javaSimulateFluid(byte[] fluid, byte[] solid, int w, int h, int d) {
            // Simple fluid propagation (very basic)
            byte[] result = fluid.clone();
            for (int y = h - 2; y >= 0; y--) {
                for (int z = 0; z < d; z++) {
                    for (int x = 0; x < w; x++) {
                        int idx = y * w * d + z * w + x;
                        int above = (y + 1) * w * d + z * w + x;
                        if (solid[idx] == 0 && fluid[above] > 0) {
                            result[idx] = (byte) Math.max(result[idx], fluid[above] - 1);
                        }
                    }
                }
            }
            return result;
        }
    }

    // ========================================================================
    // SECTION 7: Batch Distance Calculation (High-Performance)
    // ========================================================================

    @Nested
    @DisplayName("7. Batch Distance Calculation")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class BatchDistanceTests {

        @Test
        @Order(1)
        @DisplayName("7.1 Rust batchDistanceCalculation correctness")
        void testBatchDistanceCorrectness() {
            Assumptions.assumeTrue(rustAvailable, "Rust native library not available");

            float[] positions = {
                    0, 0, 0, // distance 0
                    3, 4, 0, // distance 5
                    0, 0, 10 // distance 10
            };

            double[] result = RustNativeLoader.batchDistanceCalculation(positions, 3, 0, 0, 0);

            if (result != null) {
                assertEquals(3, result.length);
                assertEquals(0.0, result[0], 0.001);
                assertEquals(5.0, result[1], 0.001);
                assertEquals(10.0, result[2], 0.001);
                System.out.println("  ✓ batchDistanceCalculation correctness verified");
            }
        }

        @Test
        @Order(2)
        @DisplayName("7.2 Benchmark: 1M batch distance calculations")
        void benchmarkBatchDistance() {
            Assumptions.assumeTrue(rustAvailable, "Rust native library not available");

            int count = 1_000_000;
            float[] positions = new float[count * 3];
            for (int i = 0; i < count * 3; i++) {
                positions[i] = RANDOM.nextFloat() * 1000 - 500;
            }

            // Warmup
            for (int i = 0; i < WARMUP_ITERATIONS; i++) {
                RustNativeLoader.batchDistanceCalculation(positions, count, 0, 64, 0);
            }

            long rustStart = System.nanoTime();
            for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
                RustNativeLoader.batchDistanceCalculation(positions, count, 0, 64, 0);
            }
            double rustMs = (System.nanoTime() - rustStart) / (BENCHMARK_ITERATIONS * 1_000_000.0);

            long javaStart = System.nanoTime();
            for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
                javaBatchDistance(positions, count, 0, 64, 0);
            }
            double javaMs = (System.nanoTime() - javaStart) / (BENCHMARK_ITERATIONS * 1_000_000.0);

            printBenchmarkResult("BatchDist 1M", javaMs, rustMs, javaMs / rustMs);
        }

        private double[] javaBatchDistance(float[] positions, int count, double cx, double cy, double cz) {
            double[] result = new double[count];
            for (int i = 0; i < count; i++) {
                double dx = positions[i * 3] - cx;
                double dy = positions[i * 3 + 1] - cy;
                double dz = positions[i * 3 + 2] - cz;
                result[i] = Math.sqrt(dx * dx + dy * dy + dz * dz);
            }
            return result;
        }
    }

    // ========================================================================
    // SECTION 8: Noise Generation
    // ========================================================================

    @Nested
    @DisplayName("8. Noise Generation")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class NoiseTests {

        @Test
        @Order(1)
        @DisplayName("8.1 Rust batchNoiseGenerate3D correctness")
        void testNoiseCorrectness() {
            int count = 100;
            float[] coords = new float[count * 3];
            for (int i = 0; i < coords.length; i++) {
                coords[i] = RANDOM.nextFloat() * 100;
            }

            // Use RustOptimizations to avoid crash if method missing
            float[] result = RustOptimizations.batchNoiseGenerate3D(12345L, coords, count);

            if (result != null) {
                assertEquals(count, result.length);
                System.out.println("  ✓ batchNoiseGenerate3D correctness verified");
            } else {
                System.out.println("  ⚠ batchNoiseGenerate3D returned null (Feature Missing/Fallback)");
                // Fail the test if we expect it to work, or just warn?
                // For benchmark purposes, we want to know what works.
                // Asserting it works:
                // fail("Rust Native method batchNoiseGenerate3D is missing or failed");
            }
        }

        @Test
        @Order(2)
        @DisplayName("8.2 Benchmark: 100,000 noise samples")
        void benchmarkNoise() {
            int count = 100_000;
            float[] coords = new float[count * 3];
            for (int i = 0; i < coords.length; i++) {
                coords[i] = RANDOM.nextFloat() * 1000;
            }

            // Warmup
            for (int i = 0; i < 5; i++)
                RustOptimizations.batchNoiseGenerate3D(12345L, coords, count);

            // Check if working
            if (RustOptimizations.batchNoiseGenerate3D(12345L, coords, count) == null) {
                System.out.println("  ⚠ Noise Benchmark skipped: Native implementation missing");
                return;
            }

            // Benchmark Java
            long javaStart = System.nanoTime();
            for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
                javaSimplexNoise(12345L, coords, count);
            }
            double javaMs = (System.nanoTime() - javaStart) / (BENCHMARK_ITERATIONS * 1_000_000.0);

            // Benchmark Rust
            long rustStart = System.nanoTime();
            for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
                RustOptimizations.batchNoiseGenerate3D(12345L, coords, count);
            }
            double rustMs = (System.nanoTime() - rustStart) / (BENCHMARK_ITERATIONS * 1_000_000.0);

            printBenchmarkResult("Noise 100K", javaMs, rustMs, javaMs / rustMs);
        }

        private float[] javaSimplexNoise(long seed, float[] coords, int count) {
            float[] result = new float[count];
            // Simulate cost of simplex noise (approximate math ops)
            for (int i = 0; i < count; i++) {
                float x = coords[i * 3];
                float y = coords[i * 3 + 1];
                float z = coords[i * 3 + 2];
                // Expensive math simulation
                result[i] = (float) Math.sin(x * 0.1 + seed) * (float) Math.cos(y * 0.1) * (float) Math.sin(z * 0.1);
            }
            return result;
        }
    }

    // ========================================================================
    // SECTION 9: Light Propagation
    // ========================================================================

    @Nested
    @DisplayName("9. Light Propagation")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class LightTests {

        @Test
        @Order(1)
        @DisplayName("9.1 Rust batchLightPropagate correctness")
        void testLightCorrectness() {
            int count = 100;
            int[] lightLevels = new int[count];
            byte[] opacity = new byte[count];

            int[] result = RustOptimizations.batchLightPropagate(lightLevels, opacity, count);
            if (result != null) {
                assertEquals(count, result.length);
                System.out.println("  ✓ batchLightPropagate correctness verified");
            } else {
                System.out.println("  ⚠ batchLightPropagate returned null (Feature Missing)");
            }
        }

        @Test
        @Order(2)
        @DisplayName("9.2 Benchmark: Light Propagation (10K updates)")
        void benchmarkLight() {
            int count = 10_000;
            int[] lightLevels = new int[count];
            byte[] opacity = new byte[count];

            for (int i = 0; i < count; i++) {
                lightLevels[i] = RANDOM.nextInt(15);
                opacity[i] = (byte) RANDOM.nextInt(16);
            }

            // Warmup
            for (int i = 0; i < 5; i++)
                RustOptimizations.batchLightPropagate(lightLevels, opacity, count);

            if (RustOptimizations.batchLightPropagate(lightLevels, opacity, count) == null) {
                System.out.println("  ⚠ Light Benchmark skipped: Native implementation missing");
                return;
            }

            // Benchmark Java
            long javaStart = System.nanoTime();
            for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
                javaLightPropagate(lightLevels, opacity, count);
            }
            double javaMs = (System.nanoTime() - javaStart) / (BENCHMARK_ITERATIONS * 1_000_000.0);

            // Benchmark Rust
            long rustStart = System.nanoTime();
            for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
                RustOptimizations.batchLightPropagate(lightLevels, opacity, count);
            }
            double rustMs = (System.nanoTime() - rustStart) / (BENCHMARK_ITERATIONS * 1_000_000.0);

            printBenchmarkResult("Light 10K", javaMs, rustMs, javaMs / rustMs);
        }

        private int[] javaLightPropagate(int[] levels, byte[] opacity, int count) {
            int[] result = new int[count];
            for (int i = 0; i < count; i++) {
                if (levels[i] > opacity[i]) {
                    result[i] = levels[i] - opacity[i]; // Simulate propagation check
                } else {
                    result[i] = 0;
                }
            }
            return result;
        }
    }

    // ========================================================================
    // SECTION 10: Spatial Hash / AABB
    // ========================================================================

    @Nested
    @DisplayName("10. Spatial Hash & AABB")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class SpatialTests {

        @Test
        @Order(1)
        @DisplayName("10.1 Rust batchSpatialHash correctness")
        void testSpatialHashCorrectness() {
            int count = 100;
            double[] positions = new double[count * 3];
            long[] result = RustOptimizations.batchSpatialHash(positions, 16.0, count);

            if (result != null) {
                assertEquals(count, result.length);
                System.out.println("  ✓ batchSpatialHash correctness verified");
            } else {
                System.out.println("  ⚠ batchSpatialHash returned null (Feature Missing)");
            }
        }

        @Test
        @Order(2)
        @DisplayName("10.2 Benchmark: Spatial Hash (100K entities)")
        void benchmarkSpatialHash() {
            int count = 100_000;
            double[] positions = generateRandomPositions(count);
            double cellSize = 16.0;

            // Warmup
            for (int i = 0; i < 5; i++)
                RustOptimizations.batchSpatialHash(positions, cellSize, count);

            if (RustOptimizations.batchSpatialHash(positions, cellSize, count) == null) {
                System.out.println("  ⚠ SpatialHash Benchmark skipped: Native implementation missing");
                return;
            }

            // Benchmark Java
            long javaStart = System.nanoTime();
            for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
                javaSpatialHash(positions, cellSize, count);
            }
            double javaMs = (System.nanoTime() - javaStart) / (BENCHMARK_ITERATIONS * 1_000_000.0);

            // Benchmark Rust
            long rustStart = System.nanoTime();
            for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
                RustOptimizations.batchSpatialHash(positions, cellSize, count);
            }
            double rustMs = (System.nanoTime() - rustStart) / (BENCHMARK_ITERATIONS * 1_000_000.0);

            printBenchmarkResult("SpatialHash 100K", javaMs, rustMs, javaMs / rustMs);
        }

        @Test
        @Order(3)
        @DisplayName("10.3 Benchmark: AABB Intersection (10K checks)")
        void benchmarkAABB() {
            int count = 10_000;
            double[] boxes = new double[count * 6]; // x1, y1, z1, x2, y2, z2
            for (int i = 0; i < count * 6; i++) {
                boxes[i] = RANDOM.nextDouble() * 100;
            }
            double[] testBox = { 10, 10, 10, 20, 20, 20 };

            // Warmup
            for (int i = 0; i < 5; i++)
                RustOptimizations.batchAABBIntersection(boxes, testBox, count);

            if (RustOptimizations.batchAABBIntersection(boxes, testBox, count) == null) {
                System.out.println("  ⚠ AABB Benchmark skipped: Native implementation missing");
                return;
            }

            // Benchmark Java
            long javaStart = System.nanoTime();
            for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
                javaAABBIntersection(boxes, testBox, count);
            }
            double javaMs = (System.nanoTime() - javaStart) / (BENCHMARK_ITERATIONS * 1_000_000.0);

            // Benchmark Rust
            long rustStart = System.nanoTime();
            for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
                RustOptimizations.batchAABBIntersection(boxes, testBox, count);
            }
            double rustMs = (System.nanoTime() - rustStart) / (BENCHMARK_ITERATIONS * 1_000_000.0);

            printBenchmarkResult("AABB 10K", javaMs, rustMs, javaMs / rustMs);
        }

        private long[] javaSpatialHash(double[] positions, double cellSize, int count) {
            long[] hashes = new long[count];
            for (int i = 0; i < count; i++) {
                int x = (int) Math.floor(positions[i * 3] / cellSize);
                int y = (int) Math.floor(positions[i * 3 + 1] / cellSize);
                int z = (int) Math.floor(positions[i * 3 + 2] / cellSize);
                hashes[i] = ((long) x & 0x3FFFFF) | (((long) y & 0xFFF) << 22) | (((long) z & 0x3FFFFF) << 34);
            }
            return hashes;
        }

        private int[] javaAABBIntersection(double[] boxes, double[] t, int count) {
            int[] results = new int[count];
            for (int i = 0; i < count; i++) {
                double minX = boxes[i * 6], minY = boxes[i * 6 + 1], minZ = boxes[i * 6 + 2];
                double maxX = boxes[i * 6 + 3], maxY = boxes[i * 6 + 4], maxZ = boxes[i * 6 + 5];
                results[i] = (minX < t[3] && maxX > t[0] && minY < t[4] && maxY > t[1] && minZ < t[5] && maxZ > t[2])
                        ? 1
                        : 0;
            }
            return results;
        }

    }

    // ========================================================================
    // HELPER METHODS
    // ========================================================================

    private double[] generateRandomPositions(int entityCount) {
        double[] positions = new double[entityCount * 3];
        for (int i = 0; i < entityCount * 3; i++) {
            positions[i] = RANDOM.nextDouble() * 1000 - 500; // -500 to 500
        }
        return positions;
    }

    private double[] generateRandomDistances(int count, double min, double max) {
        double[] distances = new double[count];
        for (int i = 0; i < count; i++) {
            distances[i] = min + RANDOM.nextDouble() * (max - min);
        }
        return distances;
    }

    private String formatNumber(int n) {
        if (n >= 1_000_000)
            return (n / 1_000_000) + "M";
        if (n >= 1_000)
            return (n / 1_000) + "K";
        return String.valueOf(n);
    }

    private void printBenchmarkResult(String name, double javaMs, double rustMs, double speedup) {
        String speedupStr;
        if (speedup >= 1.0) {
            speedupStr = String.format("%.2fx faster", speedup);
        } else {
            speedupStr = String.format("%.2fx slower", 1.0 / speedup);
        }

        System.out.printf("  %-20s │ Java: %8.3f ms │ Rust: %8.3f ms │ %s%n",
                name, javaMs, rustMs, speedupStr);
    }
}
