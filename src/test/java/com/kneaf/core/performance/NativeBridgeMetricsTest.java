package com.kneaf.core.performance;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class NativeBridgeMetricsTest {
  @Test
  void testQueueDepthAndAvgProcessing() throws Exception {
    try {
      System.loadLibrary("rustperf");
    } catch (Throwable t) {
      Assumptions.assumeTrue(false);
    }

    long h = NativeBridge.nativeCreateWorker(1);
    assertNotEquals(0L, h);

    int initial = RustPerformance.nativeGetWorkerQueueDepth();

    // Push 10 echo tasks
    for (int i = 0; i < 10; i++) {
      TaskEnvelope t = new TaskEnvelope(i + 1, TaskEnvelope.TYPE_ECHO, ("x" + i).getBytes());
      NativeBridge.nativePushTask(h, t.toBytes());
    }

    int afterPush = RustPerformance.nativeGetWorkerQueueDepth();
    assertTrue(afterPush >= initial);

    // wait for results up to 5s
    int got = 0;
    long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
    while (System.nanoTime() < deadline && got < 10) {
      byte[] res = NativeBridge.nativePollResult(h);
      if (res != null) got++;
      else Thread.sleep(5);
    }

    assertEquals(10, got);
    double avgMs = RustPerformance.nativeGetWorkerAvgProcessingMs();
    assertTrue(avgMs >= 0.0);

    NativeBridge.nativeDestroyWorker(h);
  }
}
