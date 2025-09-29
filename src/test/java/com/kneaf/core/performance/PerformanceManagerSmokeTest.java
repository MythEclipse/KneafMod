package com.kneaf.core.performance;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PerformanceManagerSmokeTest {
    @Test
    void loadConfigAndClasses() {
        PerformanceConfig cfg = PerformanceConfig.load();
        assertNotNull(cfg, "Config should load");
        // Ensure getter works
        assertTrue(cfg.getNetworkExecutorPoolSize() >= 1, "Network executor pool size should be >= 1");

        // Verify the configured network executor pool size can be used to create an executor (no Minecraft classes)
        java.util.concurrent.ScheduledExecutorService exec = null;
        try {
            exec = java.util.concurrent.Executors.newScheduledThreadPool(cfg.getNetworkExecutorPoolSize());
            assertNotNull(exec, "Executor should be creatable with configured pool size");
        } finally {
            if (exec != null) exec.shutdownNow();
        }
    }
}
