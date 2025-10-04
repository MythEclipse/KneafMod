package com.kneaf.core.performance.debug;

import com.kneaf.core.performance.monitoring.PerformanceManager;
import com.kneaf.core.performance.core.PerformanceConstants;
import com.kneaf.core.performance.bridge.NativeBridgeUtils;

public class AdaptiveProbe {
    public static void main(String[] args) {
        double tps = PerformanceManager.getAverageTPS();
        long tickDelay = PerformanceManager.getLastTickDurationMs();

        System.out.println("Current avgTps=" + String.format("%.2f", tps) + " tickDelayMs=" + tickDelay);

        // PerformanceOptimizer-like calculations
        final int BASE_MAX_ENTITIES = 200;
        final int BASE_MAX_ITEMS = 300;
        final int BASE_MAX_MOBS = 150;
        final int BASE_MAX_BLOCKS = 500;
        final double BASE_TARGET_TICK_TIME_MS = 50.0;

        double tpsFactor = Math.max(0.5, Math.min(1.5, tps / 20.0));
        double delayFactor = 1.0;
        if (tickDelay > BASE_TARGET_TICK_TIME_MS) {
            delayFactor = Math.max(0.5, BASE_TARGET_TICK_TIME_MS / (double) tickDelay);
        }
        int maxEntities = (int) Math.max(10, BASE_MAX_ENTITIES * tpsFactor * delayFactor);
        int maxItems = (int) Math.max(10, BASE_MAX_ITEMS * tpsFactor);
        int maxMobs = (int) Math.max(5, BASE_MAX_MOBS * Math.max(0.4, Math.min(1.2, tps / 20.0)));
        int maxBlocks = (int) Math.max(10, BASE_MAX_BLOCKS * Math.max(0.6, Math.min(1.3, tps / 20.0)));

        System.out.println("Adaptive PerformanceOptimizer values:");
        System.out.println("  maxEntitiesPerTick=" + maxEntities);
        System.out.println("  maxItemsPerTick=" + maxItems);
        System.out.println("  maxMobsPerTick=" + maxMobs);
        System.out.println("  maxBlocksPerTick=" + maxBlocks);
        System.out.println("  targetTickTimeMs=" + (BASE_TARGET_TICK_TIME_MS * (20.0 / Math.max(0.1, tps))));
        System.out.println("  optimizationThreshold=" + (int)Math.max(1, 25 * (tps>=20.0?1.0:Math.max(0.4, tps/20.0))));

        // BatchProcessor-like calculations
        int baseBatch = PerformanceConstants.DEFAULT_BATCH_SIZE;
        double tpsFactorBatch = Math.max(0.5, Math.min(1.5, tps / 20.0));
        double delayFactorBatch = 1.0;
        if (tickDelay > 50) delayFactorBatch = Math.max(0.5, 50.0 / (double) tickDelay);
        int batchSize = Math.max(1, (int)(NativeBridgeUtils.calculateOptimalBatchSize(baseBatch,25,200) * tpsFactorBatch * delayFactorBatch));
        long batchTimeout = (tps < 15.0) ? Math.max(20, PerformanceConstants.DEFAULT_BATCH_TIMEOUT_MS * 2) : (tps < 18.0 ? Math.max(10, PerformanceConstants.DEFAULT_BATCH_TIMEOUT_MS) : PerformanceConstants.DEFAULT_BATCH_TIMEOUT_MS);
        int batchSleep = (tps < 15.0) ? Math.max(1, PerformanceConstants.BATCH_PROCESSOR_SLEEP_MS / 2) : (tps < 18.0 ? PerformanceConstants.BATCH_PROCESSOR_SLEEP_MS : Math.max(1, PerformanceConstants.BATCH_PROCESSOR_SLEEP_MS * 2));

        System.out.println("Adaptive BatchProcessor values:");
        System.out.println("  batchSize=" + batchSize);
        System.out.println("  batchTimeoutMs=" + batchTimeout);
        System.out.println("  batchProcessorSleepMs=" + batchSleep);

        // EntityProcessor-like
        double closeRadius = Math.max(8.0, 16.0 * (tps / 20.0));
        double mediumRadius = Math.max(16.0, 32.0 * (tps / 20.0));
        double closeRate = Math.max(0.2, Math.min(1.0, tps / 20.0));
        double mediumRate = Math.max(0.1, Math.min(0.8, (tps / 20.0) * 0.5));
        double farRate = Math.max(0.05, Math.min(0.5, (20.0 - tps) / 20.0 * 0.2 + 0.1));
        boolean spatial = tps >= 12.0;
        int quadtreeEntities = Math.max(100, (int)(1000 * Math.max(0.5, Math.min(1.5, tps / 20.0))));
        int quadtreeDepth = tps > 18.0 ? 10 : (tps > 14.0 ? 8 : 6);

        System.out.println("Adaptive EntityProcessor values:");
        System.out.println("  closeRadius=" + closeRadius);
        System.out.println("  mediumRadius=" + mediumRadius);
        System.out.println("  closeRate=" + closeRate);
        System.out.println("  mediumRate=" + mediumRate);
        System.out.println("  farRate=" + farRate);
        System.out.println("  spatialPartitioning=" + spatial);
        System.out.println("  quadtreeMaxEntities=" + quadtreeEntities);
        System.out.println("  quadtreeMaxDepth=" + quadtreeDepth);
    }
}
