package com.kneaf.core.performance;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

public class NativeBridgeRoundtripTest {

    @Test
    public void testWorkerRoundtrip() throws Exception {
        // Skip if native lib not loaded
        try {
            System.loadLibrary("rustperf");
        } catch (Throwable t) {
            Assumptions.assumeTrue(false, "native library not available");
        }

        long handle = NativeBridge.nativeCreateWorker(1);
        assertTrue(handle != 0L);

    TaskEnvelope t = new TaskEnvelope(42L, (byte)1, "hello".getBytes());
    byte[] payload = t.toBytes();

        NativeBridge.nativePushTask(handle, payload);

        // poll for up to 2s
        byte[] res = null;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            res = NativeBridge.nativePollResult(handle);
            if (res != null) break;
            Thread.sleep(20);
        }

        assertNotNull(res, "expected a result from native worker");
        ResultEnvelope out = ResultEnvelope.fromBytes(res);
        assertEquals(42L, out.taskId);
        assertEquals(0, out.status);
        assertArrayEquals("hello".getBytes(), out.payload);

        NativeBridge.nativeDestroyWorker(handle);
    }
}
