package com.kneaf.core.performance;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class PerformanceManagerSmokeTest {
  @Test
  void loadConfigAndClasses() {
    com.kneaf.core.performance.monitoring.PerformanceConfig cfg = com.kneaf.core.performance.monitoring.PerformanceConfig.load();
    assertNotNull(cfg, "Config should load");
    // Ensure getter works
    assertTrue(cfg.getNetworkExecutorpoolSize() >= 1, "Network executor pool size should be >= 1");

    // Verify the configured network executor pool size can be used to create an executor (no
    // Minecraft classes)
    java.util.concurrent.ScheduledExecutorService exec = null;
    try {
      exec =
          java.util.concurrent.Executors.newScheduledThreadPool(cfg.getNetworkExecutorpoolSize());
      assertNotNull(exec, "Executor should be creatable with configured pool size");
    } finally {
      if (exec != null) exec.shutdownNow();
    }
  }
}
