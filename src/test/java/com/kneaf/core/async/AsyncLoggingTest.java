package com.kneaf.core.async;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test untuk AsyncLogger dan AsyncMetricsCollector.
 */
public class AsyncLoggingTest {
    
    private AsyncLogger logger;
    private AsyncMetricsCollector metrics;
    
    @BeforeEach
    public void setup() {
        logger = new AsyncLogger("TestLogger");
        metrics = new AsyncMetricsCollector();
    }
    
    @AfterEach
    public void teardown() {
        logger.shutdown();
        metrics.shutdown();
    }
    
    @Test
    public void testAsyncLoggerNonBlocking() throws InterruptedException {
        long startTime = System.nanoTime();
        
        // Log 10000 messages
        for (int i = 0; i < 10000; i++) {
            logger.info("Test message {}", i);
        }
        
        long endTime = System.nanoTime();
        long durationMs = (endTime - startTime) / 1_000_000;
        
        // Should complete very quickly (< 100ms) karena non-blocking
        assertTrue(durationMs < 100, "Logging took too long: " + durationMs + "ms");
        
        // Wait untuk processing
        Thread.sleep(500);
        
        // Check statistics
        AsyncLogger.LoggerStatistics stats = logger.getStatistics();
        assertTrue(stats.messagesLogged > 0, "No messages logged");
        
        // Under extreme load (10,000 messages from single thread very fast),
        // some drops are acceptable. The key is that it doesn't block.
        // Allow up to 30% drop rate under this extreme stress test
        double dropRate = (double) stats.messagesDropped / 10000.0;
        assertTrue(dropRate < 0.30, "Too many messages dropped: " + stats.messagesDropped);
    }
    
    @Test
    public void testLazyEvaluation() throws InterruptedException {
        AtomicInteger evaluations = new AtomicInteger(0);
        
        // Lazy evaluation should not execute if log level disabled
        logger.trace(() -> {
            evaluations.incrementAndGet();
            return "Expensive trace";
        });
        
        // Trace is typically disabled, so evaluation should not happen
        assertEquals(0, evaluations.get(), "Lazy evaluation executed when it shouldn't");
        
        // But info should execute
        logger.info(() -> {
            evaluations.incrementAndGet();
            return "Expensive info";
        });
        
        // Wait untuk async processing
        Thread.sleep(100);
        
        // Should have evaluated once
        assertTrue(evaluations.get() > 0, "Lazy evaluation didn't execute for info");
    }
    
    @Test
    public void testMetricsCollectorNonBlocking() {
        long startTime = System.nanoTime();
        
        // Record 100000 metrics
        for (int i = 0; i < 100000; i++) {
            metrics.incrementCounter("test.counter");
            metrics.recordGauge("test.gauge", i);
            metrics.recordHistogram("test.histogram", i);
        }
        
        long endTime = System.nanoTime();
        long durationMs = (endTime - startTime) / 1_000_000;
        
        // Should complete very quickly (< 100ms) karena lock-free
        assertTrue(durationMs < 100, "Metrics collection took too long: " + durationMs + "ms");
        
        // Verify counts
        long counter = metrics.getCounter("test.counter");
        assertEquals(100000, counter, "Counter value incorrect");
    }
    
    @Test
    public void testMetricsTimer() throws InterruptedException {
        // Use timer
        try (var timer = metrics.startTimer("test.timer")) {
            Thread.sleep(10); // Sleep 10ms
        }
        
        // Record a few more
        for (int i = 0; i < 10; i++) {
            try (var timer = metrics.startTimer("test.timer")) {
                Thread.sleep(1);
            }
        }
        
        // Get stats
        var stats = metrics.getTimerStats("test.timer");
        assertTrue(stats.count > 0, "No timer records");
        assertTrue(stats.avgNs > 0, "Average time is zero");
    }
    
    @Test
    public void testHistogramStats() {
        // Record some values
        for (int i = 1; i <= 100; i++) {
            metrics.recordHistogram("test.hist", i);
        }
        
        var stats = metrics.getHistogramStats("test.hist");
        assertEquals(100, stats.count, "Count mismatch");
        assertEquals(1, stats.min, "Min mismatch");
        assertEquals(100, stats.max, "Max mismatch");
        assertTrue(stats.avg > 40 && stats.avg < 60, "Average not in expected range");
    }
    
    @Test
    public void testAsyncLoggingManager() {
        AsyncLoggingManager.initialize();
        
        var logger2 = AsyncLoggingManager.getAsyncLogger("Test2");
        logger2.info("Test message from manager");
        
        var stats = AsyncLoggingManager.getStatistics();
        assertTrue(stats.initialized, "Not initialized");
        assertTrue(stats.enabled, "Not enabled");
        assertTrue(stats.loggerStats.activeLoggers > 0, "No active loggers");
    }
    
    @Test
    public void testConcurrentLogging() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(10);
        
        // Create 10 threads yang log concurrently
        for (int t = 0; t < 10; t++) {
            final int threadId = t;
            new Thread(() -> {
                for (int i = 0; i < 1000; i++) {
                    logger.info("Thread {} message {}", threadId, i);
                    metrics.incrementCounter("thread." + threadId);
                }
                latch.countDown();
            }).start();
        }
        
        // Wait untuk completion
        assertTrue(latch.await(5, TimeUnit.SECONDS), "Threads didn't complete in time");
        
        // Wait untuk processing (longer untuk allow buffer processing)
        Thread.sleep(1000);
        
        // Verify most messages logged (allow for some drops under heavy load)
        var stats = logger.getStatistics();
        long totalExpected = 10000;
        long totalProcessed = stats.messagesLogged;
        
        // Should have logged at least 80% of messages
        assertTrue(totalProcessed >= totalExpected * 0.8, 
            "Too few messages logged: " + totalProcessed + " / " + totalExpected);
        
        // Drop rate should be reasonable
        double dropRate = stats.getDropRate();
        assertTrue(dropRate < 0.3, "Drop rate too high: " + (dropRate * 100) + "%");
    }
    
    @Test
    public void testSampling() {
        metrics.setSamplingRate(50); // 50% sampling
        
        for (int i = 0; i < 1000; i++) {
            metrics.incrementCounter("sampled.counter");
        }
        
        long count = metrics.getCounter("sampled.counter");
        // Should be roughly 500 (±20%)
        assertTrue(count > 400 && count < 600, "Sampling rate incorrect: " + count);
        
        // Reset to 100%
        metrics.setSamplingRate(100);
    }
}
