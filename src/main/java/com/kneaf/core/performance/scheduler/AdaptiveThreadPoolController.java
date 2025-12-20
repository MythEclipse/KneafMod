package com.kneaf.core.performance.scheduler;

import com.kneaf.core.EntityProcessingService;
import com.kneaf.core.model.EntityProcessingStatistics;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dynamically adjusts thread pool size based on system load
 */
public class AdaptiveThreadPoolController implements Runnable {
    private static final Logger LOGGER = LoggerFactory.getLogger(AdaptiveThreadPoolController.class);
    private static final int MIN_THREADS = 2;
    private static final int MAX_THREADS = Runtime.getRuntime().availableProcessors();
    private static final double TARGET_CPU_LOAD = 0.75;
    private static final double LOAD_HYSTERESIS = 0.1;
    private static final int ADJUSTMENT_COOLDOWN_MS = 5000;
    private static final int SAMPLE_WINDOW_SIZE = 10;

    private final EntityProcessingService service;
    private final ConcurrentLinkedQueue<Double> recentLoadSamples;
    private long lastAdjustmentTime;

    public AdaptiveThreadPoolController(EntityProcessingService service) {
        this.service = service;
        this.recentLoadSamples = new ConcurrentLinkedQueue<>();
        this.lastAdjustmentTime = System.currentTimeMillis();
    }

    @Override
    public void run() {
        try {
            EntityProcessingStatistics stats = service.getStatistics();
            double currentLoad = stats.cpuUsage / 100.0; // Normalized 0-1
            addLoadSample(currentLoad);

            long currentTime = System.currentTimeMillis();
            if (currentTime - lastAdjustmentTime < ADJUSTMENT_COOLDOWN_MS) {
                return;
            }

            double avgLoad = getSmoothedLoadAverage();
            int currentThreads = service.getCurrentThreadPoolSize();
            int newThreads = currentThreads;

            // Adjust thread pool based on load and queue depth
            boolean highQueuePressure = stats.queueSize > currentThreads * 10;
            boolean slowProcessing = stats.averageProcessingTimeMs > 50.0; // >50ms per entity is slow

            if ((avgLoad < TARGET_CPU_LOAD - LOAD_HYSTERESIS || highQueuePressure) && currentThreads < MAX_THREADS) {
                // Scale up if CPU headroom exists OR queue is backing up
                newThreads = Math.min(currentThreads + 1, MAX_THREADS);
                if (newThreads != currentThreads) {
                    LOGGER.info("Scaling up thread pool: {} -> {} (Load: {:.2f}, Queue: {})",
                            currentThreads, newThreads, avgLoad, stats.queueSize);
                }
            } else if (avgLoad > TARGET_CPU_LOAD + LOAD_HYSTERESIS && !highQueuePressure && !slowProcessing
                    && currentThreads > MIN_THREADS) {
                // Scale down if CPU is hot AND queue is manageable
                newThreads = Math.max(currentThreads - 1, MIN_THREADS);
                if (newThreads != currentThreads) {
                    LOGGER.info("Scaling down thread pool: {} -> {} (Load: {:.2f})",
                            currentThreads, newThreads, avgLoad);
                }
            }

            if (newThreads != currentThreads) {
                service.updateThreadPoolSize(newThreads);
                lastAdjustmentTime = currentTime;
            }

        } catch (Exception e) {
            LOGGER.error("Error in thread pool controller: {}", e.getMessage(), e);
        }
    }

    private void addLoadSample(double load) {
        recentLoadSamples.offer(load);
        while (recentLoadSamples.size() > SAMPLE_WINDOW_SIZE) {
            recentLoadSamples.poll();
        }
    }

    private double getSmoothedLoadAverage() {
        if (recentLoadSamples.isEmpty())
            return 0.0;
        return recentLoadSamples.stream().mapToDouble(d -> d).average().orElse(0.0);
    }
}
