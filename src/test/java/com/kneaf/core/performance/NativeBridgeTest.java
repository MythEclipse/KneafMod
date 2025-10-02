package com.kneaf.core.performance;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assertions;
import java.util.concurrent.locks.LockSupport;

class NativeBridgeTest {
    @Test
    void testCreatePushPollDestroy() {
        try {
            long h = NativeBridge.nativeCreateWorker(1);
            if (h == 0) {
                // native library not present — skip
                System.err.println("nativeCreateWorker returned 0 (native missing?)");
                return;
            }

            byte[] payload = new byte[] {1,2,3,4};
            TaskEnvelope task = new TaskEnvelope(1L, (byte)1, payload);
            NativeBridge.nativePushTask(h, task.toBytes());

            // busy-wait small amount for result to propagate (MVP)
            byte[] res = null;
            for (int i=0;i<50;i++) {
                res = NativeBridge.nativePollResult(h);
                if (res != null) break;
                // small park to wait for native worker to process (MVP test)
                LockSupport.parkNanos(10_000_000L);
            }

            Assertions.assertNotNull(res);
            ResultEnvelope result = ResultEnvelope.fromBytes(res);
            Assertions.assertEquals(1L, result.taskId);
            Assertions.assertEquals(0, result.status);
            Assertions.assertArrayEquals(payload, result.payload);

            NativeBridge.nativeDestroyWorker(h);
        } catch (UnsatisfiedLinkError e) {
            System.err.println("Native lib not loaded for tests: " + e.getMessage());
        }
    }
}
