package com.kneaf.core.performance.scheduler;

import com.kneaf.core.ParallelRustVectorProcessor;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicInteger;

import com.kneaf.core.performance.model.VectorOperation;

/**
 * Cache-optimized work-stealing scheduler.
 * Extracted from ParallelRustVectorProcessor.
 */
public class CacheOptimizedWorkStealingScheduler {
    private final ConcurrentLinkedQueue<VectorOperation> globalQueue;
    private final ConcurrentLinkedQueue<VectorOperation>[] workerQueues;
    private final AtomicInteger successfulSteals;
    private final AtomicInteger stealAttempts;
    private final BlockDistribution blockDistribution;

    @SuppressWarnings("unchecked")
    public CacheOptimizedWorkStealingScheduler(int numWorkers) {
        this.globalQueue = new ConcurrentLinkedQueue<>();
        this.workerQueues = new ConcurrentLinkedQueue[numWorkers];
        this.successfulSteals = new AtomicInteger(0);
        this.stealAttempts = new AtomicInteger(0);
        this.blockDistribution = new BlockDistribution();

        for (int i = 0; i < numWorkers; i++) {
            workerQueues[i] = new ConcurrentLinkedQueue<>();
        }
    }

    public void submit(VectorOperation operation) {
        globalQueue.offer(operation);
    }

    public void submitToWorker(int workerId, VectorOperation operation) {
        if (workerId >= 0 && workerId < workerQueues.length) {
            workerQueues[workerId].offer(operation);
        } else {
            globalQueue.offer(operation);
        }
    }

    public VectorOperation stealWithCacheAffinity(int workerId,
            List<Integer> preferredBlocks) {
        stealAttempts.incrementAndGet();

        // Try to steal from workers that own preferred blocks
        for (int block : preferredBlocks) {
            Integer targetWorker = blockDistribution.getBlockOwner(block);
            if (targetWorker != null && targetWorker != workerId) {
                VectorOperation task = workerQueues[targetWorker].poll();
                if (task != null) {
                    successfulSteals.incrementAndGet();
                    return task;
                }
            }
        }

        // Fallback to regular stealing
        return steal(workerId);
    }

    public VectorOperation steal(int workerId) {
        stealAttempts.incrementAndGet();

        // Try other worker queues
        for (int i = 0; i < workerQueues.length; i++) {
            if (i == workerId)
                continue;

            VectorOperation task = workerQueues[i].poll();
            if (task != null) {
                successfulSteals.incrementAndGet();
                return task;
            }
        }

        // Try global queue as fallback
        return globalQueue.poll();
    }

    public VectorOperation getLocalTask(int workerId) {
        if (workerId >= 0 && workerId < workerQueues.length) {
            return workerQueues[workerId].poll();
        }
        return null;
    }

    public double getStealSuccessRate() {
        int attempts = stealAttempts.get();
        if (attempts == 0)
            return 0.0;
        return (double) successfulSteals.get() / attempts;
    }

    public int getSuccessfulSteals() {
        return successfulSteals.get();
    }

    public int getStealAttempts() {
        return stealAttempts.get();
    }

    public BlockDistribution getBlockDistribution() {
        return blockDistribution;
    }
}
