package com.kneaf.core.performance;

import com.kneaf.core.performance.error.RustPerformanceError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Abstract base class for Rust performance monitoring system.
 * Contains common functionality used across all performance-related classes.
 */
public abstract class RustPerformanceBase {
    protected static final Logger LOGGER = LoggerFactory.getLogger(RustPerformanceBase.class);
    
    // Common state variables
    // Common configuration constants
    public static final int DEFAULT_MIN_BATCH_SIZE = 50;
    public static final int DEFAULT_MAX_BATCH_SIZE = 500;
    public static final long DEFAULT_ADAPTIVE_TIMEOUT_MS = 1;
    public static final int DEFAULT_WORKER_THREADS = 8;
    
    // Common state variables (static for shared access across all implementations)
    protected static final AtomicBoolean nativeLibraryLoaded = new AtomicBoolean(false);
    protected static final AtomicBoolean initialized = new AtomicBoolean(false);
    protected static final AtomicLong monitoringStartTime = new AtomicLong(0);
    
    /**
     * Initialize common state variables (static initialization).
     */
    protected static void initCommonState() {
        nativeLibraryLoaded.set(false);
        initialized.set(false);
        monitoringStartTime.set(0);
    }

    /**
     * Safe native call wrapper for boolean return types.
     *
     * @param callable The native call to execute
     * @param error    The error to throw if the call fails
     * @param check    The initialization check to perform before the call
     * @return The result of the native call
     */

    /**
     * Safe native call wrapper for string return types.
     *
     * @param callable The native call to execute
     * @param error    The error to throw if the call fails
     * @param check    The initialization check to perform before the call
     * @return The result of the native call
     */
    /**
     * Safe native call wrapper for string return types.
     *
     * @param callable The native call to execute
     * @param error    The error to throw if the call fails
     * @param check    The initialization check to perform before the call
     * @return The result of the native call
     */
    public static String safeNativeStringCall(Supplier<String> callable, RustPerformanceError error, BooleanSupplier check) {
        return safeNativeStringCall(callable, error, check, false);
    }

    /**
     * Safe native call wrapper with nullable result support.
     *
     * @param callable The native call to execute
     * @param error    The error to throw if the call fails
     * @param check    The initialization check to perform before the call
     * @param nullable Whether the result can be null
     * @return The result of the native call
     */
    public static String safeNativeStringCall(Supplier<String> callable, RustPerformanceError error, BooleanSupplier check, boolean nullable) {
        if (!check.getAsBoolean()) {
            throw new IllegalStateException(error.getMessage());
        }
        
        try {
            String result = callable.get();
            if (!nullable && result == null) {
                throw new RuntimeException("Native call returned null result");
            }
            return result;
        } catch (Exception e) {
            LOGGER.error("Native string call failed: {}", error.getMessage(), e);
            throw new RuntimeException(error.getMessage(), e);
        }
    }

    /**
     * Safe native call wrapper for byte array return types.
     *
     * @param callable The native call to execute
     * @param error    The error to throw if the call fails
     * @param check    The initialization check to perform before the call
     * @return The result of the native call
     */
    public static byte[] safeNativeByteArrayCall(Supplier<byte[]> callable, RustPerformanceError error, BooleanSupplier check) {
        if (!check.getAsBoolean()) {
            throw new IllegalStateException(error.getMessage());
        }
        
        try {
            byte[] result = callable.get();
            if (result == null) {
                throw new RuntimeException("Native call returned null result");
            }
            return result;
        } catch (Exception e) {
            LOGGER.error("Native byte array call failed: {}", error.getMessage(), e);
            throw new RuntimeException(error.getMessage(), e);
        }
    }

    /**
     * Safe native call wrapper for long return types.
     *
     * @param callable The native call to execute
     * @param error    The error to throw if the call fails
     * @param check    The initialization check to perform before the call
     * @return The result of the native call
     */
    public static long safeNativeLongCall(Supplier<Long> callable, RustPerformanceError error, BooleanSupplier check) {
        if (!check.getAsBoolean()) {
            throw new IllegalStateException(error.getMessage());
        }
        
        try {
            return callable.get();
        } catch (Exception e) {
            LOGGER.error("Native long call failed: {}", error.getMessage(), e);
            throw new RuntimeException(error.getMessage(), e);
        }
    }

    /**
     * Safe native call wrapper for boolean return types.
     *
     * @param callable The native call to execute
     * @param error    The error to throw if the call fails
     * @param check    The initialization check to perform before the call
     * @return The result of the native call
     */
    public static boolean safeNativeBooleanCall(Supplier<Boolean> callable, RustPerformanceError error, BooleanSupplier check) {
        if (!check.getAsBoolean()) {
            throw new IllegalStateException(error.getMessage());
        }
        
        try {
            return callable.get();
        } catch (Exception e) {
            LOGGER.error("Native boolean call failed: {}", error.getMessage(), e);
            throw new RuntimeException(error.getMessage(), e);
        }
    }

    /**
     * Safe native call wrapper for double return types.
     *
     * @param callable The native call to execute
     * @param error    The error to throw if the call fails
     * @param check    The initialization check to perform before the call
     * @return The result of the native call
     */
    public static double safeNativeDoubleCall(Supplier<Double> callable, RustPerformanceError error, BooleanSupplier check) {
        if (!check.getAsBoolean()) {
            throw new IllegalStateException(error.getMessage());
        }
        
        try {
            return callable.get();
        } catch (Exception e) {
            LOGGER.error("Native double call failed: {}", error.getMessage(), e);
            throw new RuntimeException(error.getMessage(), e);
        }
    }

    /**
     * Safe native call wrapper for void return types.
     *
     * @param callable The native call to execute
     * @param error    The error to throw if the call fails
     * @param check    The initialization check to perform before the call
     */
    public static void safeNativeVoidCall(Runnable callable, RustPerformanceError error, BooleanSupplier check) {
        if (!check.getAsBoolean()) {
            throw new IllegalStateException(error.getMessage());
        }
        
        try {
            callable.run();
        } catch (Exception e) {
            LOGGER.error("Native void call failed: {}", error.getMessage(), e);
            throw new RuntimeException(error.getMessage(), e);
        }
    }

    /**
     * Check if the system is initialized.
     *
     * @return true if initialized, false otherwise
     */
    /**
     * Check if the system is initialized.
     *
     * @return true if initialized, false otherwise
     */
    public static boolean isInitialized() {
        return initialized.get();
    }

    /**
     * Get performance monitoring uptime in milliseconds.
     *
     * @return Uptime in milliseconds
     */
    public static long getUptimeMs() {
        long startTime = monitoringStartTime.get();
        if (startTime == 0) {
            return 0;
        }
        return System.currentTimeMillis() - startTime;
    }


    /**
     * Validate that a list of operations is not null or empty.
     *
     * @param operations The list to validate
     * @throws IllegalArgumentException If the list is null or empty
     */
    protected static void validateOperations(List<byte[]> operations) {
        if (operations == null || operations.isEmpty()) {
            throw new IllegalArgumentException("Operations list cannot be null or empty");
        }
    }

    /**
     * Validate that priorities match the number of operations.
     *
     * @param operations The list of operations
     * @param priorities The list of priorities
     * @throws IllegalArgumentException If priorities don't match operations count
     */
    protected static void validatePriorities(List<byte[]> operations, List<Byte> priorities) {
        if (priorities == null || priorities.size() != operations.size()) {
            throw new IllegalArgumentException("Priorities must match operations count");
        }
    }

    /**
     * Validate that a ByteBuffer is direct.
     *
     * @param buffer The buffer to validate
     * @throws IllegalArgumentException If the buffer is not direct
     */
    protected static void validateDirectBuffer(ByteBuffer buffer) {
        if (buffer == null || !buffer.isDirect()) {
            throw new IllegalArgumentException("Buffer must be a direct ByteBuffer");
        }
    }
}