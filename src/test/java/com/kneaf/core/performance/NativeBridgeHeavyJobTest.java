package com.kneaf.core.performance;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

import static org.junit.jupiter.api.Assertions.*;

public class NativeBridgeHeavyJobTest {

    @Test
    void testHeavyJobSumOfSquares() throws Exception {
        try {
            System.loadLibrary("rustperf");
        } catch (Throwable t) {
            Assumptions.assumeTrue(false, "native library not available");
        }

        long handle = NativeBridge.nativeCreateWorker(1);
        assertTrue(handle != 0L);

        // n = 10000
        String payload = "10000";
        TaskEnvelope t = new TaskEnvelope(12345L, (byte)2, payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        NativeBridge.nativePushTask(handle, t.toBytes());

        // poll
        byte[] res = null;
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            res = NativeBridge.nativePollResult(handle);
            if (res != null) break;
            Thread.sleep(10);
        }

        assertNotNull(res);
        ResultEnvelope out = ResultEnvelope.fromBytes(res);
        assertEquals(12345L, out.taskId);
        assertEquals(0, out.status);

        String json = new String(out.payload, java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(json.contains("\"task\":\"heavy\""));
        assertTrue(json.contains("\"n\":10000"));
        assertTrue(json.contains("\"sum\":"));

        NativeBridge.nativeDestroyWorker(handle);
    }
}
