package com.kneaf.core.unifiedbridge;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Unified worker thread lifecycle management system.
 * Manages native worker threads with proper lifecycle management, pooling, and monitoring.
 */
public final class WorkerManager {
    private static final Logger LOGGER = Logger.getLogger(WorkerManager.class.getName());
    private static final WorkerManager INSTANCE = new WorkerManager();
    
    private BridgeConfiguration config;
    private final ConcurrentMap<Long, Worker> activeWorkers = new ConcurrentHashMap<>();
    private final AtomicLong nextWorkerHandle = new AtomicLong(1);
    private final AtomicLong totalTasksProcessed = new AtomicLong(0);
    private final AtomicLong totalWorkersCreated = new AtomicLong(0);
    private final AtomicLong totalWorkersDestroyed = new AtomicLong(0);
    
    // For connection pooling support
    private final ConcurrentMap<Long, ConnectionPool> connectionPools = new ConcurrentHashMap<>();
    private final AtomicLong nextPoolHandle = new AtomicLong(1);

    private WorkerManager() {
        this.config = BridgeConfiguration.getDefault();
        LOGGER.info("WorkerManager initialized with default configuration");
    }

    /**
     * Get the singleton instance of WorkerManager.
     * @return WorkerManager instance
     */
    public static WorkerManager getInstance() {
        return INSTANCE;
    }

    /**
     * Get the singleton instance with custom configuration.
     * @param config Custom configuration
     * @return WorkerManager instance
     */
    public static WorkerManager getInstance(BridgeConfiguration config) {
        INSTANCE.config = Objects.requireNonNull(config);
        LOGGER.info("WorkerManager reconfigured with custom settings");
        return INSTANCE;
    }

    /**
     * Create a new native worker with specified concurrency level.
     * @param concurrency Number of concurrent threads to use
     * @return Unique worker handle (non-zero if successful)
     */
    public long createWorker(int concurrency) {
        long handle = nextWorkerHandle.getAndIncrement();
        int effectiveConcurrency = Math.max(1, concurrency);
        
        Worker worker = new Worker(handle, effectiveConcurrency);
        activeWorkers.put(handle, worker);
        
        totalWorkersCreated.incrementAndGet();
        LOGGER.fine(() -> String.format("Created worker %d with concurrency %d", handle, effectiveConcurrency));
        
        return handle;
    }

    /**
     * Create a worker with default concurrency from configuration.
     * @return Unique worker handle (non-zero if successful)
     */
    public long createWorker() {
        return createWorker(config.getDefaultWorkerConcurrency());
    }

    /**
     * Destroy a native worker and release all associated resources.
     * @param workerHandle Handle of worker to destroy
     * @return true if worker was found and destroyed, false otherwise
     */
    public boolean destroyWorker(long workerHandle) {
        Worker worker = activeWorkers.remove(workerHandle);
        if (worker != null) {
            worker.destroy();
            totalWorkersDestroyed.incrementAndGet();
            LOGGER.fine(() -> String.format("Destroyed worker %d", workerHandle));
            return true;
        }
        LOGGER.warning(() -> String.format("Attempted to destroy non-existent worker %d", workerHandle));
        return false;
    }

    /**
     * Get a worker by handle (for internal use only).
     * @param workerHandle Handle of worker to retrieve
     * @return Worker instance or null if not found
     */
    Worker getWorker(long workerHandle) {
        return activeWorkers.get(workerHandle);
    }

    /**
     * Get statistics about worker management.
     * @return Map containing worker statistics
     */
    public Map<String, Object> getWorkerStats() {
        return Map.of(
                "activeWorkers", activeWorkers.size(),
                "totalWorkersCreated", totalWorkersCreated.get(),
                "totalWorkersDestroyed", totalWorkersDestroyed.get(),
                "totalTasksProcessed", getTotalTasksProcessed(),
                "defaultConcurrency", config.getDefaultWorkerConcurrency()
        );
    }
    
    /**
     * Record a task processed globally.
     */
    public void recordGlobalTaskProcessed() {
        totalTasksProcessed.incrementAndGet();
    }
    
    /**
     * Get total tasks processed.
     */
    public long getTotalTasksProcessed() {
        return totalTasksProcessed.get();
    }

    /**
     * Create a connection pool for managing multiple worker connections.
     * @param poolSize Number of workers in the pool
     * @return Unique pool handle (non-zero if successful)
     */
    public long createConnectionPool(int poolSize) {
        long handle = nextPoolHandle.getAndIncrement();
        int effectivePoolSize = Math.max(2, Math.min(poolSize, config.getMaxConnectionPoolSize()));
        
        ConnectionPool pool = new ConnectionPool(handle, effectivePoolSize);
        connectionPools.put(handle, pool);
        
        LOGGER.fine(() -> String.format("Created connection pool %d with size %d", handle, effectivePoolSize));
        return handle;
    }

    /**
     * Destroy a connection pool and all associated workers.
     * @param poolHandle Handle of pool to destroy
     * @return true if pool was found and destroyed, false otherwise
     */
    public boolean destroyConnectionPool(long poolHandle) {
        ConnectionPool pool = connectionPools.remove(poolHandle);
        if (pool != null) {
            pool.destroy();
            LOGGER.fine(() -> String.format("Destroyed connection pool %d", poolHandle));
            return true;
        }
        LOGGER.warning(() -> String.format("Attempted to destroy non-existent pool %d", poolHandle));
        return false;
    }

    /**
     * Get a connection from the pool (acquires and releases automatically).
     * @param poolHandle Handle of pool to use
     * @param task Task to execute with the connection
     * @param <T> Return type of the task
     * @return Result of the task execution
     */
    public <T> T withConnection(long poolHandle, WorkerTask<T> task) {
        ConnectionPool pool = connectionPools.get(poolHandle);
        if (pool == null) {
            throw new IllegalArgumentException("Invalid connection pool handle: " + poolHandle);
        }
        
        long workerHandle = pool.acquireConnection();
        try {
            return task.execute(workerHandle);
        } finally {
            pool.releaseConnection(workerHandle);
        }
    }

    /**
     * Get statistics about connection pools.
     * @return Map containing connection pool statistics
     */
    public Map<String, Object> getConnectionPoolStats() {
        return Map.of(
                "activePools", connectionPools.size(),
                "defaultPoolSize", config.getConnectionPoolSize(),
                "maxPoolSize", config.getMaxConnectionPoolSize()
        );
    }

    /**
     * Shutdown all workers and connection pools gracefully.
     */
    public void shutdown() {
        LOGGER.info("WorkerManager shutting down - destroying all workers and pools");
        
        // Destroy all connection pools first
        for (long poolHandle : new java.util.ArrayList<>(connectionPools.keySet())) {
            destroyConnectionPool(poolHandle);
        }
        
        // Destroy all remaining workers
        for (long workerHandle : new java.util.ArrayList<>(activeWorkers.keySet())) {
            destroyWorker(workerHandle);
        }
        
        LOGGER.info("WorkerManager shutdown complete");
    }

    /**
     * Interface for tasks that need to use a worker connection.
     * @param <T> Return type of the task
     */
    @FunctionalInterface
    public interface WorkerTask<T> {
        /**
         * Execute a task with the given worker handle.
         * @param workerHandle Handle of worker to use
         * @return Result of the task
         */
        T execute(long workerHandle);
    }

    /**
     * Internal representation of a native worker.
     */
    static final class Worker {
        private final long handle;
        private final int concurrency;
        private final AtomicLong tasksProcessed = new AtomicLong(0);
        private volatile boolean running = true;
        private Thread workerThread;

        Worker(long handle, int concurrency) {
            this.handle = handle;
            this.concurrency = concurrency;
            
            // Start worker thread (simplified implementation - would call native code in real scenario)
            this.workerThread = new Thread(this::workerLoop, "UnifiedBridge-Worker-" + handle);
            this.workerThread.setDaemon(true);
            this.workerThread.start();
        }

        void workerLoop() {
            try {
                while (running) {
                    // In real implementation, this would wait for tasks from a queue
                    // and call native processing functions
                    Thread.sleep(10);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOGGER.fine(() -> String.format("Worker %d interrupted", handle));
            } finally {
                LOGGER.fine(() -> String.format("Worker %d loop exiting", handle));
            }
        }

        void destroy() {
            running = false;
            if (workerThread != null) {
                workerThread.interrupt();
                try {
                    workerThread.join(500);
                } catch (InterruptedException e) {
                    LOGGER.log(Level.WARNING, "Timeout waiting for worker thread to terminate", e);
                }
            }
            LOGGER.fine(() -> String.format("Worker %d destroyed", handle));
        }

        void recordTaskProcessed() {
            tasksProcessed.incrementAndGet();
            WorkerManager.getInstance().recordGlobalTaskProcessed();
        }

        long getTasksProcessed() {
            return tasksProcessed.get();
        }
    }

    /**
     * Internal representation of a connection pool.
     */
    static final class ConnectionPool {
        private final long handle;
        private final int poolSize;
        private final ConcurrentMap<Long, Worker> availableWorkers = new ConcurrentHashMap<>();
        private final ConcurrentMap<Long, Worker> inUseWorkers = new ConcurrentHashMap<>();

        ConnectionPool(long handle, int poolSize) {
            this.handle = handle;
            this.poolSize = poolSize;
            
            // Initialize pool with workers
            for (int i = 0; i < poolSize; i++) {
                long workerHandle = WorkerManager.getInstance().createWorker();
                Worker worker = WorkerManager.getInstance().getWorker(workerHandle);
                if (worker != null) {
                    availableWorkers.put(workerHandle, worker);
                }
            }
        }

        long acquireConnection() {
            // Try to get an available worker first
            for (long workerHandle : availableWorkers.keySet()) {
                Worker worker = availableWorkers.remove(workerHandle);
                if (worker != null) {
                    inUseWorkers.put(workerHandle, worker);
                    return workerHandle;
                }
            }
            
            // If no available workers and pool is not full, create a new one
            if (inUseWorkers.size() < poolSize) {
                long workerHandle = WorkerManager.getInstance().createWorker();
                Worker worker = WorkerManager.getInstance().getWorker(workerHandle);
                if (worker != null) {
                    inUseWorkers.put(workerHandle, worker);
                    return workerHandle;
                }
            }
            
            // Wait briefly for a worker to become available (simplified)
            try {
                Thread.sleep(10);
                for (long workerHandle : availableWorkers.keySet()) {
                    Worker worker = availableWorkers.remove(workerHandle);
                    if (worker != null) {
                        inUseWorkers.put(workerHandle, worker);
                        return workerHandle;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            
            throw new RuntimeException("No available workers in pool " + handle);
        }

        void releaseConnection(long workerHandle) {
            Worker worker = inUseWorkers.remove(workerHandle);
            if (worker != null) {
                availableWorkers.put(workerHandle, worker);
            }
        }

        void destroy() {
            // Return all workers to available pool first
            inUseWorkers.forEach((handle, worker) -> availableWorkers.put(handle, worker));
            inUseWorkers.clear();
            
            // Destroy all workers
            for (long workerHandle : availableWorkers.keySet()) {
                WorkerManager.getInstance().destroyWorker(workerHandle);
            }
            availableWorkers.clear();
            
            LOGGER.fine(() -> String.format("Connection pool %d destroyed", handle));
        }
    }
}