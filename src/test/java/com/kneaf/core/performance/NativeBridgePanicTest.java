package com.kneaf.core.performance;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

import static org.junit.jupiter.api.Assertions.*;

class NativeBridgePanicTest {
    @Test
    void testPanicReturnsErrorEnvelope() throws Exception {
        try { System.loadLibrary("kneaf"); } catch (Throwable t) { Assumptions.assumeTrue(false); }

        long h = NativeBridge.nativeCreateWorker(1);
        assertNotEquals(0L, h);

        TaskEnvelope t = new TaskEnvelope(999L, TaskEnvelope.TYPE_PANIC_TEST, new byte[0]);
        NativeBridge.nativePushTask(h, t.toBytes());

        byte[] res = null;
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            res = NativeBridge.nativePollResult(h);
            if (res != null) break;
            Thread.sleep(20);
        }

        assertNotNull(res);
        ResultEnvelope out = ResultEnvelope.fromBytes(res);
        assertEquals(999L, out.taskId);
        assertEquals(ResultEnvelope.STATUS_ERROR, out.status);
        String msg = out.payloadAsString();
        assertTrue(msg.contains("panic"));

        NativeBridge.nativeDestroyWorker(h);
    }
}
