package com.kneaf.core.unifiedbridge;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import com.kneaf.core.unifiedbridge.RustMemoryManager.MemoryHandle;

/**
 * Unified JNI interface aligned with Rust patterns for efficient cross-language calls.
 * Implements thin layer design with direct mapping to Rust functions and types.
 */
public final class RustBridge {
    private static final RustBridge INSTANCE = new RustBridge();
    private static final AtomicLong NEXT_WORKER_ID = new AtomicLong(1);
    private static final AtomicLong NEXT_OPERATION_ID = new AtomicLong(1);
    
    // Track active workers for cleanup
    private final Map<Long, WorkerHandle> activeWorkers = new ConcurrentHashMap<>();
    
    // Native methods - direct mapping to Rust JNI exports
    private static class Native {
        // Worker management
        public static native long nativeCreateWorker(int concurrency);
        public static native void nativeDestroyWorker(long workerHandle);
        
        // Task execution
        public static native void nativePushTask(long workerHandle, byte[] payload);
        public static native byte[] nativePollResult(long workerHandle);
        
        // Zero-copy operations
        public static native long nativeSubmitZeroCopyOperation(long workerHandle, ByteBuffer buffer, int operationType);
        public static native ByteBuffer nativePollZeroCopyResult(long operationId);
        
        // Batch operations
        public static native byte[] nativeExecuteSync(String operationName, long memoryHandle);
        public static native byte[] nativeExecuteBatch(byte[][] operations);
        
        // Resource management
        public static native void nativeInitAllocator();
        public static native void nativeShutdownAllocator();
        
        // Status checks
        public static native boolean nativeIsAvailable();
    }
    
    private RustBridge() {
        // Initialize native library on first use
        if (!Native.nativeIsAvailable()) {
            throw new IllegalStateException("Rust native library not available");
        }
        Native.nativeInitAllocator();
    }
    
    /**
     * Get singleton instance of RustBridge.
     * @return RustBridge instance
     */
    public static RustBridge getInstance() {
        return INSTANCE;
    }
    
    /**
     * Create a new worker with specified concurrency level.
     * @param concurrency Number of threads to use
     * @return WorkerHandle for managing the worker
     */
    public WorkerHandle createWorker(int concurrency) {
        Objects.requireNonNull(concurrency >= 1, "Concurrency must be at least 1");
        
        long handle = Native.nativeCreateWorker(concurrency);
        if (handle == 0) {
            throw new RustBridgeException("Failed to create worker", RustBridgeException.ErrorType.WORKER_CREATION_FAILED);
        }
        
        WorkerHandle worker = new WorkerHandle(handle);
        activeWorkers.put(handle, worker);
        return worker;
    }
    
    /**
     * Execute a synchronous operation with Rust.
     * @param operationName Name of the operation to execute
     * @param parameters Operation parameters as byte array
     * @return Result of the operation as byte array
     */
    public byte[] executeSync(String operationName, byte[] parameters) {
        Objects.requireNonNull(operationName, "Operation name cannot be null");
        
        try (MemoryHandle handle = RustMemoryManager.getInstance().allocate(parameters != null ? parameters : new byte[0])) {
            // Use memory ID from RustMemoryManager instead of raw bytes
            return Native.nativeExecuteSync(operationName, handle.getId());
        } catch (Exception e) {
            throw new RustBridgeException("Failed to execute sync operation: " + operationName,
                RustBridgeException.ErrorType.NATIVE_CALL_FAILED, e);
        }
    }
    
    /**
     * Execute a batch of operations with Rust.
     * @param operations Array of operations (each operation is a byte array)
     * @return Result of the batch operation as byte array
     */
    public byte[] executeBatch(byte[][] operations) {
        Objects.requireNonNull(operations, "Operations cannot be null");
        
        try {
            return Native.nativeExecuteBatch(operations);
        } catch (Exception e) {
            throw new RustBridgeException("Failed to execute batch operation", 
                RustBridgeException.ErrorType.BATCH_PROCESSING_FAILED, e);
        }
    }
    
    /**
     * Submit a zero-copy operation to Rust.
     * @param workerHandle Handle of the worker to use
     * @param buffer Direct ByteBuffer for zero-copy transfer
     * @param operationType Type of operation to perform
     * @return Operation ID for polling results
     */
    public long submitZeroCopyOperation(WorkerHandle workerHandle, ByteBuffer buffer, int operationType) {
        Objects.requireNonNull(workerHandle, "Worker handle cannot be null");
        Objects.requireNonNull(buffer, "Buffer cannot be null");
        Objects.requireNonNull(buffer.isDirect(), "Buffer must be direct");
        
        try {
            return Native.nativeSubmitZeroCopyOperation(workerHandle.getHandle(), buffer, operationType);
        } catch (Exception e) {
            throw new RustBridgeException("Failed to submit zero-copy operation", 
                RustBridgeException.ErrorType.BUFFER_ALLOCATION_FAILED, e);
        }
    }
    
    /**
     * Poll for result of a zero-copy operation.
     * @param operationId ID of the operation to poll
     * @return Result as ByteBuffer or null if not available
     */
    public ByteBuffer pollZeroCopyResult(long operationId) {
        try {
            return Native.nativePollZeroCopyResult(operationId);
        } catch (Exception e) {
            throw new RustBridgeException("Failed to poll zero-copy result", 
                RustBridgeException.ErrorType.RESULT_POLLING_FAILED, e);
        }
    }
    
    /**
     * Shutdown the RustBridge and clean up resources.
     */
    public void shutdown() {
        // Clean up all active workers
        activeWorkers.values().forEach(WorkerHandle::destroy);
        activeWorkers.clear();
        
        // Shutdown native allocator
        try {
            Native.nativeShutdownAllocator();
        } catch (Exception e) {
            // Log but don't throw - we're shutting down
            System.err.println("Warning: Failed to shutdown native allocator: " + e.getMessage());
        }
    }
    
    /**
     * Handle for managing a worker instance.
     */
    public static final class WorkerHandle {
        private final long handle;
        
        private WorkerHandle(long handle) {
            this.handle = handle;
        }
        
        /**
         * Get the native worker handle.
         * @return Native handle value
         */
        public long getHandle() {
            return handle;
        }
        
        /**
         * Push a task to this worker.
         * @param payload Task payload
         */
        public void pushTask(byte[] payload) {
            Objects.requireNonNull(payload, "Payload cannot be null");
            
            try {
                Native.nativePushTask(handle, payload);
            } catch (Exception e) {
                throw new RustBridgeException("Failed to push task to worker", 
                    RustBridgeException.ErrorType.TASK_PROCESSING_FAILED, e);
            }
        }
        
        /**
         * Poll for results from this worker.
         * @return Result payload or empty array if none available
         */
        public byte[] pollResult() {
            try {
                byte[] result = Native.nativePollResult(handle);
                return result != null ? result : new byte[0];
            } catch (Exception e) {
                throw new RustBridgeException("Failed to poll result from worker", 
                    RustBridgeException.ErrorType.RESULT_POLLING_FAILED, e);
            }
        }
        
        /**
         * Destroy this worker and clean up resources.
         */
        void destroy() {
            try {
                Native.nativeDestroyWorker(handle);
                INSTANCE.activeWorkers.remove(handle);
            } catch (Exception e) {
                throw new RustBridgeException("Failed to destroy worker",
                    RustBridgeException.ErrorType.WORKER_DESTROY_FAILED, e);
            }
        }
    }
    
    /**
     * Exception class for RustBridge errors, aligned with Rust error patterns.
     */
    public static final class RustBridgeException extends RuntimeException {
        public enum ErrorType {
            GENERIC_ERROR,
            WORKER_CREATION_FAILED,
            WORKER_DESTROY_FAILED,
            TASK_PROCESSING_FAILED,
            RESULT_POLLING_FAILED,
            BUFFER_ALLOCATION_FAILED,
            BATCH_PROCESSING_FAILED,
            NATIVE_CALL_FAILED,
            RESOURCE_MANAGEMENT_ERROR
        }
        
        private final ErrorType errorType;
        
        public RustBridgeException(String message, ErrorType errorType) {
            super(message);
            this.errorType = errorType;
        }
        
        public RustBridgeException(String message, ErrorType errorType, Throwable cause) {
            super(message, cause);
            this.errorType = errorType;
        }
        
        public ErrorType getErrorType() {
            return errorType;
        }
    }
}