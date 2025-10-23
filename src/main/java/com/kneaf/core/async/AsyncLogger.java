package com.kneaf.core.async;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Asynchronous logger wrapper yang tidak memblokir thread utama.
 * Semua operasi logging dilakukan di background thread menggunakan lock-free queue.
 * 
 * Features:
 * - Lock-free disruptor-style ring buffer untuk performa maksimal
 * - Dedicated logging thread untuk menghindari blocking
 * - Auto-batching untuk efisiensi I/O
 * - Graceful degradation saat queue penuh (drop dengan warning)
 * - Lazy message evaluation untuk menghindari object creation di hot path
 */
public final class AsyncLogger {
    private static final int RING_BUFFER_SIZE = 8192; // Must be power of 2
    private static final int BATCH_SIZE = 32;
    private static final int MAX_DRAIN_WAIT_MS = 5000;
    
    private final Logger delegate;
    private final LogEntry[] ringBuffer;
    private final AtomicLong writeSequence = new AtomicLong(0);
    private final AtomicLong readSequence = new AtomicLong(0);
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Thread loggingThread;
    
    // Statistics
    private final AtomicLong messagesLogged = new AtomicLong(0);
    private final AtomicLong messagesDropped = new AtomicLong(0);
    private final AtomicLong batchesProcessed = new AtomicLong(0);
    
    // Entry types
    private enum LogLevel {
        TRACE, DEBUG, INFO, WARN, ERROR
    }
    
    // Ring buffer entry
    private static class LogEntry {
        volatile LogLevel level;
        volatile Supplier<String> messageSupplier;
        volatile Object[] args;
        volatile Throwable throwable;
        volatile long sequence;
        
        void clear() {
            level = null;
            messageSupplier = null;
            args = null;
            throwable = null;
            sequence = -1;
        }
    }
    
    public AsyncLogger(Class<?> clazz) {
        this(LoggerFactory.getLogger(clazz));
    }
    
    public AsyncLogger(String name) {
        this(LoggerFactory.getLogger(name));
    }
    
    public AsyncLogger(Logger delegate) {
        this.delegate = delegate;
        this.ringBuffer = new LogEntry[RING_BUFFER_SIZE];
        
        // Pre-allocate ring buffer entries
        for (int i = 0; i < RING_BUFFER_SIZE; i++) {
            ringBuffer[i] = new LogEntry();
        }
        
        // Start background logging thread
        this.loggingThread = new Thread(this::processLogEntries, "AsyncLogger-" + delegate.getName());
        this.loggingThread.setDaemon(true);
        this.loggingThread.setPriority(Thread.NORM_PRIORITY - 1); // Slightly lower priority
        this.loggingThread.start();
    }
    
    /**
     * Log messages dengan lazy evaluation untuk menghindari string formatting di hot path
     */
    public void trace(Supplier<String> messageSupplier) {
        if (delegate.isTraceEnabled()) {
            enqueue(LogLevel.TRACE, messageSupplier, null, null);
        }
    }
    
    public void trace(String message, Object... args) {
        if (delegate.isTraceEnabled()) {
            enqueue(LogLevel.TRACE, () -> message, args, null);
        }
    }
    
    public void debug(Supplier<String> messageSupplier) {
        if (delegate.isDebugEnabled()) {
            enqueue(LogLevel.DEBUG, messageSupplier, null, null);
        }
    }
    
    public void debug(String message, Object... args) {
        if (delegate.isDebugEnabled()) {
            enqueue(LogLevel.DEBUG, () -> message, args, null);
        }
    }
    
    public void info(Supplier<String> messageSupplier) {
        if (delegate.isInfoEnabled()) {
            enqueue(LogLevel.INFO, messageSupplier, null, null);
        }
    }
    
    public void info(String message, Object... args) {
        if (delegate.isInfoEnabled()) {
            enqueue(LogLevel.INFO, () -> message, args, null);
        }
    }
    
    public void warn(Supplier<String> messageSupplier) {
        if (delegate.isWarnEnabled()) {
            enqueue(LogLevel.WARN, messageSupplier, null, null);
        }
    }
    
    public void warn(String message, Object... args) {
        if (delegate.isWarnEnabled()) {
            enqueue(LogLevel.WARN, () -> message, args, null);
        }
    }
    
    public void warn(String message, Throwable throwable) {
        if (delegate.isWarnEnabled()) {
            enqueue(LogLevel.WARN, () -> message, null, throwable);
        }
    }
    
    public void error(Supplier<String> messageSupplier) {
        if (delegate.isErrorEnabled()) {
            enqueue(LogLevel.ERROR, messageSupplier, null, null);
        }
    }
    
    public void error(String message, Object... args) {
        if (delegate.isErrorEnabled()) {
            enqueue(LogLevel.ERROR, () -> message, args, null);
        }
    }
    
    public void error(String message, Throwable throwable) {
        if (delegate.isErrorEnabled()) {
            enqueue(LogLevel.ERROR, () -> message, null, throwable);
        }
    }
    
    /**
     * Enqueue log entry ke ring buffer (lock-free)
     */
    private void enqueue(LogLevel level, Supplier<String> messageSupplier, Object[] args, Throwable throwable) {
        long currentSeq = writeSequence.getAndIncrement();
        long wrapPoint = currentSeq - RING_BUFFER_SIZE;
        long cachedReadSeq = readSequence.get();
        
        // Check if buffer is full (wait a bit for space)
        if (wrapPoint > cachedReadSeq) {
            // Buffer is full - drop message untuk avoid blocking
            messagesDropped.incrementAndGet();
            
            // Fallback to synchronous logging untuk critical errors
            if (level == LogLevel.ERROR && throwable != null) {
                delegate.error(messageSupplier.get(), throwable);
            }
            return;
        }
        
        // Write to ring buffer
        int index = (int) (currentSeq & (RING_BUFFER_SIZE - 1));
        LogEntry entry = ringBuffer[index];
        
        entry.level = level;
        entry.messageSupplier = messageSupplier;
        entry.args = args;
        entry.throwable = throwable;
        entry.sequence = currentSeq;
        
        messagesLogged.incrementAndGet();
    }
    
    /**
     * Background thread untuk process log entries dalam batch
     */
    private void processLogEntries() {
        final LogEntry[] batch = new LogEntry[BATCH_SIZE];
        
        while (running.get() || readSequence.get() < writeSequence.get()) {
            try {
                int batchSize = 0;
                long currentRead = readSequence.get();
                long availableSeq = writeSequence.get();
                
                // Collect batch
                while (batchSize < BATCH_SIZE && currentRead < availableSeq) {
                    int index = (int) (currentRead & (RING_BUFFER_SIZE - 1));
                    LogEntry entry = ringBuffer[index];
                    
                    if (entry.sequence == currentRead && entry.level != null) {
                        batch[batchSize++] = entry;
                        currentRead++;
                    } else {
                        break;
                    }
                }
                
                // Process batch
                if (batchSize > 0) {
                    for (int i = 0; i < batchSize; i++) {
                        processLogEntry(batch[i]);
                        batch[i].clear();
                    }
                    readSequence.addAndGet(batchSize);
                    batchesProcessed.incrementAndGet();
                } else {
                    // No work, sleep briefly
                    Thread.sleep(1);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                // Log to delegate directly untuk avoid infinite loop
                delegate.error("Error in async logging thread", e);
            }
        }
    }
    
    /**
     * Process single log entry
     */
    private void processLogEntry(LogEntry entry) {
        try {
            String message = entry.messageSupplier.get();
            
            switch (entry.level) {
                case TRACE:
                    if (entry.args != null) {
                        delegate.trace(message, entry.args);
                    } else {
                        delegate.trace(message);
                    }
                    break;
                    
                case DEBUG:
                    if (entry.args != null) {
                        delegate.debug(message, entry.args);
                    } else {
                        delegate.debug(message);
                    }
                    break;
                    
                case INFO:
                    if (entry.args != null) {
                        delegate.info(message, entry.args);
                    } else {
                        delegate.info(message);
                    }
                    break;
                    
                case WARN:
                    if (entry.throwable != null) {
                        delegate.warn(message, entry.throwable);
                    } else if (entry.args != null) {
                        delegate.warn(message, entry.args);
                    } else {
                        delegate.warn(message);
                    }
                    break;
                    
                case ERROR:
                    if (entry.throwable != null) {
                        delegate.error(message, entry.throwable);
                    } else if (entry.args != null) {
                        delegate.error(message, entry.args);
                    } else {
                        delegate.error(message);
                    }
                    break;
            }
        } catch (Exception e) {
            delegate.error("Failed to process log entry", e);
        }
    }
    
    /**
     * Shutdown async logger dan flush semua pending messages
     */
    public void shutdown() {
        running.set(false);
        
        try {
            loggingThread.join(MAX_DRAIN_WAIT_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        // Log final statistics
        delegate.info("AsyncLogger shutdown - Messages logged: {}, dropped: {}, batches: {}", 
            messagesLogged.get(), messagesDropped.get(), batchesProcessed.get());
    }
    
    /**
     * Get statistics
     */
    public LoggerStatistics getStatistics() {
        return new LoggerStatistics(
            messagesLogged.get(),
            messagesDropped.get(),
            batchesProcessed.get(),
            writeSequence.get() - readSequence.get()
        );
    }
    
    public static class LoggerStatistics {
        public final long messagesLogged;
        public final long messagesDropped;
        public final long batchesProcessed;
        public final long pendingMessages;
        
        public LoggerStatistics(long messagesLogged, long messagesDropped, 
                              long batchesProcessed, long pendingMessages) {
            this.messagesLogged = messagesLogged;
            this.messagesDropped = messagesDropped;
            this.batchesProcessed = batchesProcessed;
            this.pendingMessages = pendingMessages;
        }
        
        public double getDropRate() {
            long total = messagesLogged + messagesDropped;
            return total > 0 ? (double) messagesDropped / total : 0.0;
        }
    }
    
    /**
     * Check log level
     */
    public boolean isTraceEnabled() {
        return delegate.isTraceEnabled();
    }
    
    public boolean isDebugEnabled() {
        return delegate.isDebugEnabled();
    }
    
    public boolean isInfoEnabled() {
        return delegate.isInfoEnabled();
    }
    
    public boolean isWarnEnabled() {
        return delegate.isWarnEnabled();
    }
    
    public boolean isErrorEnabled() {
        return delegate.isErrorEnabled();
    }
}
