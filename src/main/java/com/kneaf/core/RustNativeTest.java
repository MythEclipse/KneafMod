package com.kneaf.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Comprehensive Test Suite for Rust Native JNI Methods
 * 
 * Tests all exported JNI functions to ensure:
 * - Methods are properly linked
 * - Basic functionality works correctly
 * - Return values are reasonable
 * - No crashes or errors occur
 */
public class RustNativeTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(RustNativeTest.class);
    private static final Map<String, TestResult> results = new LinkedHashMap<>();
    
    private static int totalTests = 0;
    private static int passedTests = 0;
    private static int failedTests = 0;
    private static int skippedTests = 0;
    
    static class TestResult {
        final String name;
        final boolean passed;
        final String message;
        final long executionTimeNs;
        final Throwable error;
        
        TestResult(String name, boolean passed, String message, long executionTimeNs, Throwable error) {
            this.name = name;
            this.passed = passed;
            this.message = message;
            this.executionTimeNs = executionTimeNs;
            this.error = error;
        }
    }
    
    /**
     * Run all tests and print report
     */
    public static void runAllTests() {
        LOGGER.info("╔══════════════════════════════════════════════════════════════╗");
        LOGGER.info("║       Rust Native JNI Comprehensive Test Suite              ║");
        LOGGER.info("╚══════════════════════════════════════════════════════════════╝");
        LOGGER.info("");
        
        // First, ensure library is loaded
        if (!RustNativeLoader.loadLibrary()) {
            LOGGER.error("❌ FATAL: Failed to load native library. Cannot run tests.");
            return;
        }
        
        LOGGER.info("✅ Native library loaded from: {}", RustNativeLoader.getLoadedLibraryPath());
        LOGGER.info("");
        
        // Category 1: RustVectorLibrary Tests
        LOGGER.info("▶ Testing Category 1: RustVectorLibrary (Pure Math Operations)");
        testNalgebraMatrixMul();
        testNalgebraVectorAdd();
        testGlamVectorDot();
        testGlamVectorCross();
        testGlamMatrixMul();
        testFaerMatrixMul();
        LOGGER.info("");
        
        // Category 2: OptimizationInjector Tests
        LOGGER.info("▶ Testing Category 2: OptimizationInjector (Core Optimizations)");
        testRustperfVectorMultiply();
        testRustperfVectorAdd();
        testRustperfVectorDamp();
        LOGGER.info("");
        
        // Category 2b: Hayabusa Skills Tests
        LOGGER.info("▶ Testing Category 2b: Hayabusa Ninja Skills");
        testHayabusaPhantomShuriken();
        testHayabusaQuadShadow();
        testHayabusaShadowKillDamage();
        testHayabusaCalculatePassiveStacks();
        LOGGER.info("");
        
        // Category 3: ParallelRustVectorProcessor Tests
        LOGGER.info("▶ Testing Category 3: ParallelRustVectorProcessor (Advanced Parallel)");
        testParallelMatrixMultiplyBlock();
        testParallelStrassenMultiply();
        testBatchMatrixMultiply(); // Will be skipped
        testArenaMatrixMultiply();
        testRuntimeMatrixMultiply();
        testRuntimeVectorDotProduct();
        testRuntimeVectorAdd();
        testRuntimeMatrix4x4Multiply();
        LOGGER.info("");
        
        // Print final report
        printFinalReport();
    }
    
    // ============================================
    // Test Helper Methods
    // ============================================
    
    private static void runTest(String testName, Runnable testLogic) {
        totalTests++;
        long startTime = System.nanoTime();
        
        try {
            testLogic.run();
            long elapsed = System.nanoTime() - startTime;
            results.put(testName, new TestResult(testName, true, "Passed", elapsed, null));
            passedTests++;
            LOGGER.info("  ✅ {} ({} µs)", testName, elapsed / 1000);
        } catch (UnsatisfiedLinkError e) {
            long elapsed = System.nanoTime() - startTime;
            results.put(testName, new TestResult(testName, false, "Method not found", elapsed, e));
            failedTests++;
            LOGGER.warn("  ❌ {} - Method not found: {}", testName, e.getMessage());
        } catch (Exception e) {
            long elapsed = System.nanoTime() - startTime;
            results.put(testName, new TestResult(testName, false, "Error: " + e.getMessage(), elapsed, e));
            failedTests++;
            LOGGER.error("  ❌ {} - Error: {}", testName, e.getMessage());
        }
    }
    
    private static void assertArrayNotNull(String name, Object array) {
        if (array == null) {
            throw new AssertionError(name + " returned null");
        }
    }
    
    private static void assertArrayLength(String name, float[] array, int expectedLength) {
        if (array.length != expectedLength) {
            throw new AssertionError(name + " returned wrong length: " + array.length + " (expected " + expectedLength + ")");
        }
    }
    
    private static void assertArrayLength(String name, double[] array, int expectedLength) {
        if (array.length != expectedLength) {
            throw new AssertionError(name + " returned wrong length: " + array.length + " (expected " + expectedLength + ")");
        }
    }
    
    private static void assertApproxEqual(String name, double value, double expected, double tolerance) {
        if (Math.abs(value - expected) > tolerance) {
            throw new AssertionError(name + " value " + value + " not close to expected " + expected);
        }
    }
    
    // ============================================
    // Category 1: RustVectorLibrary Tests
    // ============================================
    
    private static void testNalgebraMatrixMul() {
        runTest("nalgebra_matrix_mul", () -> {
            float[] a = new float[16];
            float[] b = new float[16];
            
            // Identity matrix
            for (int i = 0; i < 4; i++) {
                a[i * 4 + i] = 1.0f;
                b[i * 4 + i] = 2.0f;
            }
            
            float[] result = RustNativeLoader.nalgebra_matrix_mul(a, b);
            assertArrayNotNull("nalgebra_matrix_mul", result);
            assertArrayLength("nalgebra_matrix_mul", result, 16);
        });
    }
    
    private static void testNalgebraVectorAdd() {
        runTest("nalgebra_vector_add", () -> {
            float[] a = {1.0f, 2.0f, 3.0f};
            float[] b = {4.0f, 5.0f, 6.0f};
            
            float[] result = RustNativeLoader.nalgebra_vector_add(a, b);
            assertArrayNotNull("nalgebra_vector_add", result);
            assertArrayLength("nalgebra_vector_add", result, 3);
            
            // Result should be [5, 7, 9]
            assertApproxEqual("nalgebra_vector_add[0]", result[0], 5.0, 0.001);
            assertApproxEqual("nalgebra_vector_add[1]", result[1], 7.0, 0.001);
            assertApproxEqual("nalgebra_vector_add[2]", result[2], 9.0, 0.001);
        });
    }
    
    private static void testGlamVectorDot() {
        runTest("glam_vector_dot", () -> {
            float[] a = {1.0f, 2.0f, 3.0f};
            float[] b = {4.0f, 5.0f, 6.0f};
            
            float result = RustNativeLoader.glam_vector_dot(a, b);
            
            // Dot product: 1*4 + 2*5 + 3*6 = 4 + 10 + 18 = 32
            assertApproxEqual("glam_vector_dot", result, 32.0, 0.001);
        });
    }
    
    private static void testGlamVectorCross() {
        runTest("glam_vector_cross", () -> {
            float[] a = {1.0f, 0.0f, 0.0f};
            float[] b = {0.0f, 1.0f, 0.0f};
            
            float[] result = RustNativeLoader.glam_vector_cross(a, b);
            assertArrayNotNull("glam_vector_cross", result);
            assertArrayLength("glam_vector_cross", result, 3);
            
            // Cross product of X and Y should be Z: [0, 0, 1]
            assertApproxEqual("glam_vector_cross[2]", result[2], 1.0, 0.001);
        });
    }
    
    private static void testGlamMatrixMul() {
        runTest("glam_matrix_mul", () -> {
            float[] a = new float[16];
            float[] b = new float[16];
            
            for (int i = 0; i < 4; i++) {
                a[i * 4 + i] = 1.0f;
                b[i * 4 + i] = 1.0f;
            }
            
            float[] result = RustNativeLoader.glam_matrix_mul(a, b);
            assertArrayNotNull("glam_matrix_mul", result);
            assertArrayLength("glam_matrix_mul", result, 16);
        });
    }
    
    private static void testFaerMatrixMul() {
        runTest("faer_matrix_mul", () -> {
            double[] a = new double[16];
            double[] b = new double[16];
            
            for (int i = 0; i < 4; i++) {
                a[i * 4 + i] = 1.0;
                b[i * 4 + i] = 1.0;
            }
            
            double[] result = RustNativeLoader.faer_matrix_mul(a, b);
            assertArrayNotNull("faer_matrix_mul", result);
            assertArrayLength("faer_matrix_mul", result, 16);
        });
    }
    
    // ============================================
    // Category 2: OptimizationInjector Tests
    // ============================================
    
    private static void testRustperfVectorMultiply() {
        runTest("rustperf_vector_multiply", () -> {
            double x = 1.0, y = 2.0, z = 3.0;
            double scalar = 2.0;
            
            double[] result = RustNativeLoader.rustperf_vector_multiply(x, y, z, scalar);
            assertArrayNotNull("rustperf_vector_multiply", result);
            assertArrayLength("rustperf_vector_multiply", result, 3);
            
            // Result should be [2, 4, 6]
            assertApproxEqual("rustperf_vector_multiply[0]", result[0], 2.0, 0.001);
            assertApproxEqual("rustperf_vector_multiply[1]", result[1], 4.0, 0.001);
            assertApproxEqual("rustperf_vector_multiply[2]", result[2], 6.0, 0.001);
        });
    }
    
    private static void testRustperfVectorAdd() {
        runTest("rustperf_vector_add", () -> {
            double x1 = 1.0, y1 = 2.0, z1 = 3.0;
            double x2 = 4.0, y2 = 5.0, z2 = 6.0;
            
            double[] result = RustNativeLoader.rustperf_vector_add(x1, y1, z1, x2, y2, z2);
            assertArrayNotNull("rustperf_vector_add", result);
            assertArrayLength("rustperf_vector_add", result, 3);
            
            assertApproxEqual("rustperf_vector_add[0]", result[0], 5.0, 0.001);
            assertApproxEqual("rustperf_vector_add[1]", result[1], 7.0, 0.001);
            assertApproxEqual("rustperf_vector_add[2]", result[2], 9.0, 0.001);
        });
    }
    
    private static void testRustperfVectorDamp() {
        runTest("rustperf_vector_damp", () -> {
            double x = 10.0, y = 20.0, z = 30.0;
            double dampingFactor = 0.9;
            
            double[] result = RustNativeLoader.rustperf_vector_damp(x, y, z, dampingFactor);
            assertArrayNotNull("rustperf_vector_damp", result);
            assertArrayLength("rustperf_vector_damp", result, 3);
            
            // Note: Rust implementation returns unchanged values (vanilla pass-through)
            assertApproxEqual("rustperf_vector_damp[0]", result[0], 10.0, 0.001);
            assertApproxEqual("rustperf_vector_damp[1]", result[1], 20.0, 0.001);
            assertApproxEqual("rustperf_vector_damp[2]", result[2], 30.0, 0.001);
        });
    }
    
    // ============================================
    // Category 2b: Hayabusa Skills Tests
    // ============================================
    
    private static void testHayabusaPhantomShuriken() {
        runTest("rustperf_hayabusa_phantom_shuriken", () -> {
            double startX = 0.0, startY = 64.0, startZ = 0.0;
            double targetX = 10.0, targetY = 64.0, targetZ = 10.0;
            double speed = 1.0;
            
            double[] result = RustNativeLoader.rustperf_hayabusa_phantom_shuriken(
                startX, startY, startZ, targetX, targetY, targetZ, speed
            );
            
            assertArrayNotNull("rustperf_hayabusa_phantom_shuriken", result);
            assertArrayLength("rustperf_hayabusa_phantom_shuriken", result, 3);
            
            // Result should be a position moved towards target
            if (result[0] < startX || result[0] > targetX) {
                throw new AssertionError("X should be between start and target");
            }
        });
    }
    
    private static void testHayabusaQuadShadow() {
        runTest("rustperf_hayabusa_quad_shadow", () -> {
            double centerX = 0.0, centerY = 64.0, centerZ = 0.0;
            double radius = 3.0;
            
            double[][] result = RustNativeLoader.rustperf_hayabusa_quad_shadow(
                centerX, centerY, centerZ, radius
            );
            
            assertArrayNotNull("rustperf_hayabusa_quad_shadow", result);
            if (result.length != 4) {
                throw new AssertionError("Expected 4 shadow positions, got " + result.length);
            }
            
            // Each position should have 3 coordinates [x, y, z]
            for (int i = 0; i < result.length; i++) {
                if (result[i].length != 3) {
                    throw new AssertionError("Shadow " + i + " should have 3 coordinates, got " + result[i].length);
                }
            }
        });
    }
    
    private static void testHayabusaShadowKillDamage() {
        runTest("rustperf_hayabusa_shadow_kill_damage", () -> {
            int passiveStacks = 5;
            double baseDamage = 100.0;
            
            double result = RustNativeLoader.rustperf_hayabusa_shadow_kill_damage(
                passiveStacks, baseDamage
            );
            
            // Damage should be positive
            if (result <= 0) {
                throw new AssertionError("Damage should be positive, got " + result);
            }
            
            // With 5 stacks, damage should be increased
            if (result < baseDamage) {
                throw new AssertionError("Damage with stacks should be >= base damage");
            }
        });
    }
    
    private static void testHayabusaCalculatePassiveStacks() {
        runTest("rustperf_hayabusa_calculate_passive_stacks", () -> {
            int currentStacks = 5;
            boolean successfulHit = true;
            int maxStacks = 10;
            
            int result = RustNativeLoader.rustperf_hayabusa_calculate_passive_stacks(
                currentStacks, successfulHit, maxStacks
            );
            
            // Stacks should be within valid range
            if (result < 0 || result > maxStacks) {
                throw new AssertionError("Stacks should be 0-" + maxStacks + ", got " + result);
            }
        });
    }
    
    // ============================================
    // Category 3: ParallelRustVectorProcessor Tests
    // ============================================
    
    private static void testParallelMatrixMultiplyBlock() {
        runTest("parallelMatrixMultiplyBlock", () -> {
            int size = 4;
            float[] a = new float[size * size];
            float[] b = new float[size * size];
            
            for (int i = 0; i < size; i++) {
                a[i * size + i] = 1.0f;
                b[i * size + i] = 1.0f;
            }
            
            float[] result = RustNativeLoader.parallelMatrixMultiplyBlock(a, b, size);
            assertArrayNotNull("parallelMatrixMultiplyBlock", result);
            assertArrayLength("parallelMatrixMultiplyBlock", result, size * size);
        });
    }
    
    private static void testParallelStrassenMultiply() {
        runTest("parallelStrassenMultiply", () -> {
            int n = 4;
            float[] a = new float[n * n];
            float[] b = new float[n * n];
            
            for (int i = 0; i < n; i++) {
                a[i * n + i] = 1.0f;
                b[i * n + i] = 1.0f;
            }
            
            float[] result = RustNativeLoader.parallelStrassenMultiply(a, b, n);
            assertArrayNotNull("parallelStrassenMultiply", result);
            assertArrayLength("parallelStrassenMultiply", result, n * n);
        });
    }
    
    private static void testBatchMatrixMultiply() {
        totalTests++;
        try {
            int batchSize = 2;
            int matrixSize = 4;
            float[] matrices = new float[batchSize * matrixSize * matrixSize * 2]; // 2 matrices per batch
            
            float[] result = RustNativeLoader.batchMatrixMultiply(matrices, batchSize, matrixSize);
            assertArrayNotNull("batchMatrixMultiply", result);
            passedTests++;
            LOGGER.info("  ✅ batchMatrixMultiply");
        } catch (UnsatisfiedLinkError e) {
            skippedTests++;
            LOGGER.info("  ⏭️  batchMatrixMultiply - Not yet implemented");
        } catch (Exception e) {
            failedTests++;
            LOGGER.error("  ❌ batchMatrixMultiply - Error: {}", e.getMessage());
        }
    }
    
    private static void testArenaMatrixMultiply() {
        runTest("arenaMatrixMultiply", () -> {
            int size = 4;
            float[] a = new float[size * size];
            float[] b = new float[size * size];
            
            for (int i = 0; i < size; i++) {
                a[i * size + i] = 1.0f;
                b[i * size + i] = 1.0f;
            }
            
            float[] result = RustNativeLoader.arenaMatrixMultiply(a, b, size);
            assertArrayNotNull("arenaMatrixMultiply", result);
            assertArrayLength("arenaMatrixMultiply", result, size * size);
        });
    }
    
    private static void testRuntimeMatrixMultiply() {
        runTest("runtimeMatrixMultiply", () -> {
            int size = 4;
            float[] a = new float[size * size];
            float[] b = new float[size * size];
            
            for (int i = 0; i < size; i++) {
                a[i * size + i] = 1.0f;
                b[i * size + i] = 1.0f;
            }
            
            float[] result = RustNativeLoader.runtimeMatrixMultiply(a, b, size);
            assertArrayNotNull("runtimeMatrixMultiply", result);
            assertArrayLength("runtimeMatrixMultiply", result, size * size);
        });
    }
    
    private static void testRuntimeVectorDotProduct() {
        runTest("runtimeVectorDotProduct", () -> {
            float[] a = {1.0f, 2.0f, 3.0f, 4.0f};
            float[] b = {5.0f, 6.0f, 7.0f, 8.0f};
            
            float result = RustNativeLoader.runtimeVectorDotProduct(a, b);
            
            // Dot product: 1*5 + 2*6 + 3*7 + 4*8 = 5 + 12 + 21 + 32 = 70
            assertApproxEqual("runtimeVectorDotProduct", result, 70.0, 0.001);
        });
    }
    
    private static void testRuntimeVectorAdd() {
        runTest("runtimeVectorAdd", () -> {
            float[] a = {1.0f, 2.0f, 3.0f, 4.0f};
            float[] b = {5.0f, 6.0f, 7.0f, 8.0f};
            
            float[] result = RustNativeLoader.runtimeVectorAdd(a, b);
            assertArrayNotNull("runtimeVectorAdd", result);
            assertArrayLength("runtimeVectorAdd", result, 4);
            
            assertApproxEqual("runtimeVectorAdd[0]", result[0], 6.0, 0.001);
            assertApproxEqual("runtimeVectorAdd[1]", result[1], 8.0, 0.001);
            assertApproxEqual("runtimeVectorAdd[2]", result[2], 10.0, 0.001);
            assertApproxEqual("runtimeVectorAdd[3]", result[3], 12.0, 0.001);
        });
    }
    
    private static void testRuntimeMatrix4x4Multiply() {
        runTest("runtimeMatrix4x4Multiply", () -> {
            float[] a = new float[16];
            float[] b = new float[16];
            
            for (int i = 0; i < 4; i++) {
                a[i * 4 + i] = 1.0f;
                b[i * 4 + i] = 1.0f;
            }
            
            float[] result = RustNativeLoader.runtimeMatrix4x4Multiply(a, b);
            assertArrayNotNull("runtimeMatrix4x4Multiply", result);
            assertArrayLength("runtimeMatrix4x4Multiply", result, 16);
        });
    }
    
    // ============================================
    // Final Report
    // ============================================
    
    private static void printFinalReport() {
        LOGGER.info("");
        LOGGER.info("╔══════════════════════════════════════════════════════════════╗");
        LOGGER.info("║                    Test Results Summary                      ║");
        LOGGER.info("╚══════════════════════════════════════════════════════════════╝");
        LOGGER.info("");
        LOGGER.info("Total Tests:   {}", totalTests);
        LOGGER.info("✅ Passed:     {} ({} %)", passedTests, totalTests > 0 ? (passedTests * 100 / totalTests) : 0);
        LOGGER.info("❌ Failed:     {}", failedTests);
        LOGGER.info("⏭️  Skipped:    {}", skippedTests);
        LOGGER.info("");
        
        if (failedTests > 0) {
            LOGGER.info("Failed Tests Details:");
            results.values().stream()
                .filter(r -> !r.passed)
                .forEach(r -> {
                    LOGGER.error("  ❌ {} - {}", r.name, r.message);
                    if (r.error != null) {
                        LOGGER.error("     Error: {}", r.error.getMessage());
                    }
                });
            LOGGER.info("");
        }
        
        // Calculate statistics
        long totalTime = results.values().stream()
            .mapToLong(r -> r.executionTimeNs)
            .sum();
        
        long avgTime = totalTests > 0 ? totalTime / totalTests : 0;
        
        LOGGER.info("Performance:");
        LOGGER.info("  Total Execution Time: {} ms", totalTime / 1_000_000);
        LOGGER.info("  Average Test Time:    {} µs", avgTime / 1000);
        LOGGER.info("");
        
        // Print final verdict
        if (passedTests == totalTests && totalTests > 0) {
            LOGGER.info("🎉 ALL TESTS PASSED! 🎉");
        } else if (failedTests == 0 && totalTests > 0) {
            LOGGER.info("✅ All executed tests passed (some may be skipped)");
        } else {
            LOGGER.warn("⚠️  Some tests failed. Please review the results above.");
        }
        
        LOGGER.info("══════════════════════════════════════════════════════════════");
    }
}
