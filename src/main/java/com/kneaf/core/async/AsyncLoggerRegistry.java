package com.kneaf.core.async;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Central registry untuk semua async loggers.
 * Menyediakan management dan koordinasi shutdown.
 */
public final class AsyncLoggerRegistry {
    private static final AsyncLoggerRegistry INSTANCE = new AsyncLoggerRegistry();
    
    private final ConcurrentHashMap<String, AsyncLogger> loggers = new ConcurrentHashMap<>();
    private final AtomicBoolean shutdownInProgress = new AtomicBoolean(false);
    private final AtomicLong totalMessagesLogged = new AtomicLong(0);
    private final AtomicLong totalMessagesDropped = new AtomicLong(0);
    
    private AsyncLoggerRegistry() {
        // Register shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdownAll, "AsyncLoggerRegistry-Shutdown"));
    }
    
    public static AsyncLoggerRegistry getInstance() {
        return INSTANCE;
    }
    
    /**
     * Get atau create async logger untuk class
     */
    public AsyncLogger getLogger(Class<?> clazz) {
        return getLogger(clazz.getName());
    }
    
    /**
     * Get atau create async logger dengan name
     */
    public AsyncLogger getLogger(String name) {
        return loggers.computeIfAbsent(name, AsyncLogger::new);
    }
    
    /**
     * Shutdown semua async loggers
     */
    public void shutdownAll() {
        if (!shutdownInProgress.compareAndSet(false, true)) {
            return; // Already shutting down
        }
        
        loggers.values().forEach(logger -> {
            try {
                AsyncLogger.LoggerStatistics stats = logger.getStatistics();
                totalMessagesLogged.addAndGet(stats.messagesLogged);
                totalMessagesDropped.addAndGet(stats.messagesDropped);
                logger.shutdown();
            } catch (Exception e) {
                // Ignore errors during shutdown
            }
        });
        
        // Print final statistics
        System.out.println("AsyncLoggerRegistry shutdown complete - Total messages logged: " + 
            totalMessagesLogged.get() + ", dropped: " + totalMessagesDropped.get());
    }
    
    /**
     * Get aggregated statistics
     */
    public RegistryStatistics getStatistics() {
        long logged = 0;
        long dropped = 0;
        long pending = 0;
        
        for (AsyncLogger logger : loggers.values()) {
            AsyncLogger.LoggerStatistics stats = logger.getStatistics();
            logged += stats.messagesLogged;
            dropped += stats.messagesDropped;
            pending += stats.pendingMessages;
        }
        
        return new RegistryStatistics(logged, dropped, pending, loggers.size());
    }
    
    public static class RegistryStatistics {
        public final long totalMessagesLogged;
        public final long totalMessagesDropped;
        public final long totalPendingMessages;
        public final int activeLoggers;
        
        public RegistryStatistics(long logged, long dropped, long pending, int loggers) {
            this.totalMessagesLogged = logged;
            this.totalMessagesDropped = dropped;
            this.totalPendingMessages = pending;
            this.activeLoggers = loggers;
        }
    }
}
