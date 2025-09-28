package com.kneaf.core.performance;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

import static org.junit.jupiter.api.Assertions.*;

public class NativeBridgeErrorHandlingTest {

    @Test
    public void testMalformedEnvelopeReturnsError() throws Exception {
        try {
            System.loadLibrary("kneaf");
        } catch (Throwable t) {
            Assumptions.assumeTrue(false, "native library not available");
        }

        long handle = NativeBridge.nativeCreateWorker(1);
        assertTrue(handle != 0L);

        // Build a malformed payload (too short)
        byte[] malformed = new byte[]{1,2,3,4,5};
        NativeBridge.nativePushTask(handle, malformed);

        // poll
        byte[] res = null;
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            res = NativeBridge.nativePollResult(handle);
            if (res != null) break;
            Thread.sleep(20);
        }

        assertNotNull(res, "expected an error result from native worker");
        ResultEnvelope out = ResultEnvelope.fromBytes(res);
        assertEquals(0L, out.taskId); // malformed had no task id
        assertEquals(1, out.status);
        // payload should be UTF-8 error message
        String msg = new String(out.payload, java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(msg.length() > 0);

        NativeBridge.nativeDestroyWorker(handle);
    }
}
