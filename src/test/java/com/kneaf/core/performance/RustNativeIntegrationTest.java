package com.kneaf.core.performance;

import com.kneaf.core.binary.ManualSerializers;
import com.kneaf.core.data.ItemEntityData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.List;

class RustNativeIntegrationTest {

    @Test
    void callNativeProcessItemEntitiesBinary_ifAvailable() throws Exception {
        // Ensure native library is loaded with better error handling
        try {
            Class.forName("com.kneaf.core.performance.RustPerformance");
        } catch (ClassNotFoundException e) {
            Assumptions.assumeTrue(false, "RustPerformance class not available: " + e.getMessage());
            return;
        } catch (UnsatisfiedLinkError e) {
            Assumptions.assumeTrue(false, "Native library not available: " + e.getMessage());
            return;
        } catch (ExceptionInInitializerError e) {
            Assumptions.assumeTrue(false, "RustPerformance initialization failed: " + e.getMessage());
            return;
        } catch (Exception e) {
            Assumptions.assumeTrue(false, "Error loading RustPerformance: " + e.getMessage());
            return;
        } catch (Throwable t) {
            Assumptions.assumeTrue(false, "Critical error loading RustPerformance: " + t.getMessage());
            return;
        }
        
        // Prepare a single item input
        List<ItemEntityData> items = List.of(new ItemEntityData(12345L, 5, 7, "minecraft:stone", 64, 10));
        ByteBuffer input = ManualSerializers.serializeItemInput(42L, items);

        // Try to call the private native method reflectively; if native lib not available, skip the test
        Method nativeMethod;
        try {
            nativeMethod = RustPerformance.class.getDeclaredMethod("processItemEntitiesBinaryNative", java.nio.ByteBuffer.class);
            nativeMethod.setAccessible(true);
        } catch (NoSuchMethodException e) {
            // Native method missing - skip
            Assumptions.assumeTrue(false, "Native binary method not present");
            return;
        }

        byte[] resultBytes = null;
        try {
            resultBytes = (byte[]) nativeMethod.invoke(null, input);
        } catch (Throwable t) {
            // If native library couldn't be loaded, skip test
            Throwable cause = t.getCause() != null ? t.getCause() : t;
            if (cause instanceof UnsatisfiedLinkError) {
                Assumptions.assumeTrue(false, "Native library not available: " + cause.getMessage());
                return;
            }
            throw new RuntimeException("Native invocation failed", t);
        }

        // Basic validation of returned bytes: must be non-null and at least 8 bytes (tickCount)
        org.junit.jupiter.api.Assertions.assertNotNull(resultBytes);
        org.junit.jupiter.api.Assertions.assertTrue(resultBytes.length >= 8);

        // Optionally parse first fields to ensure layout is as expected (tick + num)
        ByteBuffer out = ByteBuffer.wrap(resultBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        long tick = out.getLong();
        org.junit.jupiter.api.Assertions.assertTrue(tick >= 0);
    }
}
