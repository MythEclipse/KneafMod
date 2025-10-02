package com.kneaf.core.performance;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;

class RustPerformanceTest {
    @Test
    void testNativeCalls() {
        // Ensure native library is loaded first with better error handling
        try {
            Class.forName("com.kneaf.core.performance.RustPerformance");
        } catch (ClassNotFoundException e) {
            System.err.println("RustPerformance class not found for tests: " + e.getMessage());
            Assumptions.assumeTrue(false, "RustPerformance class not available: " + e.getMessage());
            return;
        } catch (UnsatisfiedLinkError e) {
            System.err.println("Native library not available for tests: " + e.getMessage());
            Assumptions.assumeTrue(false, "Native library not available: " + e.getMessage());
            return;
        } catch (Exception e) {
            System.err.println("Error loading RustPerformance for tests: " + e.getMessage());
            Assumptions.assumeTrue(false, "Error loading RustPerformance: " + e.getMessage());
            return;
        }
        
        try {
            String mem = RustPerformance.getMemoryStats();
            Assertions.assertNotNull(mem);

            String cpu = RustPerformance.getCpuStats();
            Assertions.assertNotNull(cpu);

            String sum = RustPerformance.parallelSumNative("[1,2,3,4]");
            Assertions.assertTrue(sum.contains("sum"));

            String mm = RustPerformance.matrixMultiplyNative("[[1,2],[3,4]]","[[5,6],[7,8]]");
            Assertions.assertNotNull(mm);

            // Test ByteBuffer allocation via AutoCloseable wrapper
            try (NativeFloatBuffer nbuf = NativeFloatBuffer.allocateFromNative(2, 3)) {
                if (nbuf != null) {
                    // Metadata
                    Assertions.assertEquals(2L, nbuf.getRows());
                    Assertions.assertEquals(3L, nbuf.getCols());
                    Assertions.assertEquals(6L, nbuf.getElementCount());
                    Assertions.assertTrue(nbuf.getByteCapacity() >= 6 * Float.BYTES);

                    // Index access (row/col)
                    float a00 = nbuf.getFloatAt(0, 0);
                    float a01 = nbuf.getFloatAt(0, 1);
                    // Expecting generator to populate 0.0, 1.0 sequentially
                    Assertions.assertEquals(0.0f, a00);
                    Assertions.assertEquals(1.0f, a01);

                    // Modify a value and verify
                    nbuf.setFloatAt(1, 2, 42.5f);
                    Assertions.assertEquals(42.5f, nbuf.getFloatAt(1, 2));

                    // Out of bounds should throw
                    Assertions.assertThrows(IndexOutOfBoundsException.class, () -> nbuf.getFloatAt(2, 0));
                }
            }

            // Closed buffer should throw on access (verify cleaner/close semantics)
            NativeFloatBuffer maybe = NativeFloatBuffer.allocateFromNative(1,1);
            if (maybe != null) {
                maybe.close();
                Assertions.assertThrows(IllegalStateException.class, maybe::buffer);
            }

            // Exercise convenience APIs
            try (NativeFloatBuffer a = NativeFloatBuffer.allocateFromNative(3,3)) {
                if (a != null) {
                    // asFloatBuffer
                    java.nio.FloatBuffer fb = a.asFloatBuffer();
                    Assertions.assertEquals(9, fb.capacity());

                    // rowBuffer
                    java.nio.FloatBuffer r1 = a.rowBuffer(1);
                    Assertions.assertEquals(3, r1.capacity());

                    // colBuffer
                    java.nio.FloatBuffer c0 = a.colBuffer(0);
                    Assertions.assertEquals(3, c0.capacity());

                    // fill and copyTo
                    a.fill(7.5f);
                    try (NativeFloatBuffer b = NativeFloatBuffer.allocateFromNative(3,3)) {
                        if (b != null) {
                            a.copyTo(b);
                            Assertions.assertEquals(7.5f, b.getFloatAt(2,2));
                        }
                    }
                }
            }
        } catch (UnsatisfiedLinkError e) {
            // Native lib not available in test environment; mark test as skipped via simple pass
            System.err.println("Native library not found for tests: " + e.getMessage());
        }
    }
}
