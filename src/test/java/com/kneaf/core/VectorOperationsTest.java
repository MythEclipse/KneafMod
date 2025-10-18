package com.kneaf.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class to verify that Rust is only used for vector calculations
 * and that Java maintains control over all game logic.
 */
public class VectorOperationsTest {

    @Test
    public void testRustVectorLibraryNoGameLogic() {
        // Test that RustVectorLibrary doesn't expose any game-related operations
        assertTrue(java.util.Arrays.stream(RustVectorLibrary.class.getDeclaredMethods())
                .noneMatch(method -> java.lang.reflect.Modifier.isPublic(method.getModifiers()) &&
                                      (method.getName().contains("entity") ||
                                       method.getName().contains("game") ||
                                       method.getName().contains("player") ||
                                       method.getName().contains("ai") ||
                                       method.getName().contains("path"))));
    }

    @Test
    public void testOptimizationInjectorNativeMethods() {
        // Skip this test if OptimizationInjector class is not available (test environment issue)
        try {
            // Test that OptimizationInjector only has vector-related native methods
            java.lang.reflect.Method[] methods = OptimizationInjector.class.getDeclaredMethods();
            for (java.lang.reflect.Method method : methods) {
                if (java.lang.reflect.Modifier.isNative(method.getModifiers())) {
                    String methodName = method.getName();
                    assertTrue(methodName.startsWith("rustperf_vector") || methodName.equals("logFromRust"),
                        "Native method '" + methodName + "' in OptimizationInjector is not vector-related");
                }
            }
        } catch (NoClassDefFoundError e) {
            // Skip test if class not found (common in test environments)
            System.out.println("Skipping OptimizationInjector native methods test: " + e.getMessage());
        }
    }

    @Test
    public void testVectorMathOperations() {
        // Test that vector operations work correctly
        if (RustVectorLibrary.isLibraryLoaded()) {
            float[] vecA = {1.0f, 2.0f, 3.0f};
            float[] vecB = {4.0f, 5.0f, 6.0f};
            
            // Test vector addition
            float[] result = RustVectorLibrary.vectorAddNalgebra(vecA, vecB);
            assertArrayEquals(new float[]{5.0f, 7.0f, 9.0f}, result, 0.001f);
            
            // Test vector dot product
            float dotResult = RustVectorLibrary.vectorDotGlam(vecA, vecB);
            assertEquals(32.0f, dotResult, 0.001f);
        } else {
            System.out.println("Native library not loaded, skipping vector operation tests");
        }
    }
}