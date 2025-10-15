package com.kneaf.core.performance;

import com.kneaf.core.performance.error.RustPerformanceError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Base class for Rust performance monitoring system.
 * Contains common functionality used across all performance-related classes.
 */
public final class RustPerformanceBase {
    public static final Logger LOGGER = LoggerFactory.getLogger(RustPerformanceBase.class);
    
    // Common configuration constants
    public static final int DEFAULT_MIN_BATCH_SIZE = 50;
    public static final int DEFAULT_MAX_BATCH_SIZE = 500;
    public static final long DEFAULT_ADAPTIVE_TIMEOUT_MS = 1;
    public static final int DEFAULT_WORKER_THREADS = 8;
    
    // Common state variables (static for shared access across all implementations)
    private static final AtomicBoolean nativeLibraryLoaded = new AtomicBoolean(false);
    private static final AtomicBoolean initialized = new AtomicBoolean(false);
    private static final AtomicLong monitoringStartTime = new AtomicLong(0);

    /**
     * Initialize common state variables (static initialization).
     */
    public static void initCommonState() {
        nativeLibraryLoaded.set(false);
        initialized.set(false);
        monitoringStartTime.set(0);
    }

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
        Objects.requireNonNull(callable, "Callable cannot be null");
        Objects.requireNonNull(error, "Error cannot be null");
        Objects.requireNonNull(check, "Check cannot be null");
        
        if (!check.getAsBoolean()) {
            throw error.asIllegalStateException();
        }
        
        try {
            String result = callable.get();
            if (!nullable && result == null) {
                throw new RuntimeException("Native call returned null result for " + error.name());
            }
            return result;
        } catch (Exception e) {
            LOGGER.error("Native string call failed: {}", error.getMessage(), e);
            throw error.asRuntimeException(e);
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
        Objects.requireNonNull(callable, "Callable cannot be null");
        Objects.requireNonNull(error, "Error cannot be null");
        Objects.requireNonNull(check, "Check cannot be null");
        
        if (!check.getAsBoolean()) {
            throw error.asIllegalStateException();
        }
        
        try {
            byte[] result = callable.get();
            if (result == null) {
                throw new RuntimeException("Native call returned null result for " + error.name());
            }
            return result;
        } catch (Exception e) {
            LOGGER.error("Native byte array call failed: {}", error.getMessage(), e);
            throw error.asRuntimeException(e);
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
        Objects.requireNonNull(callable, "Callable cannot be null");
        Objects.requireNonNull(error, "Error cannot be null");
        Objects.requireNonNull(check, "Check cannot be null");
        
        if (!check.getAsBoolean()) {
            throw error.asIllegalStateException();
        }
        
        try {
            return Objects.requireNonNull(callable.get(), "Native call returned null result for " + error.name());
        } catch (Exception e) {
            LOGGER.error("Native long call failed: {}", error.getMessage(), e);
            throw error.asRuntimeException(e);
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
        Objects.requireNonNull(callable, "Callable cannot be null");
        Objects.requireNonNull(error, "Error cannot be null");
        Objects.requireNonNull(check, "Check cannot be null");
        
        if (!check.getAsBoolean()) {
            throw error.asIllegalStateException();
        }
        
        try {
            return Objects.requireNonNull(callable.get(), "Native call returned null result for " + error.name());
        } catch (Exception e) {
            LOGGER.error("Native boolean call failed: {}", error.getMessage(), e);
            throw error.asRuntimeException(e);
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
        Objects.requireNonNull(callable, "Callable cannot be null");
        Objects.requireNonNull(error, "Error cannot be null");
        Objects.requireNonNull(check, "Check cannot be null");
        
        if (!check.getAsBoolean()) {
            throw error.asIllegalStateException();
        }
        
        try {
            return Objects.requireNonNull(callable.get(), "Native call returned null result for " + error.name());
        } catch (Exception e) {
            LOGGER.error("Native double call failed: {}", error.getMessage(), e);
            throw error.asRuntimeException(e);
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
        Objects.requireNonNull(callable, "Callable cannot be null");
        Objects.requireNonNull(error, "Error cannot be null");
        Objects.requireNonNull(check, "Check cannot be null");
        
        if (!check.getAsBoolean()) {
            throw error.asIllegalStateException();
        }
        
        try {
            callable.run();
        } catch (Exception e) {
            LOGGER.error("Native void call failed: {}", error.getMessage(), e);
            throw error.asRuntimeException(e);
        }
    }

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
        return startTime == 0 ? 0 : System.currentTimeMillis() - startTime;
    }

    /**
     * Record for native call results with error handling.
     * Uses Java 16+ record feature for immutable data carrier.
     */
    public record NativeCallResult<T>(T result, RustPerformanceError error) {
        /**
         * Create a successful result.
         *
         * @param result The result
         * @return A successful NativeCallResult
         * @param <T> The type of the result
         */
        public static <T> NativeCallResult<T> success(T result) {
            return new NativeCallResult<>(result, null);
        }

        /**
         * Create an error result.
         *
         * @param error The error
         * @return An error NativeCallResult
         * @param <T> The type of the result
         */
        public static <T> NativeCallResult<T> error(RustPerformanceError error) {
            return new NativeCallResult<>(null, Objects.requireNonNull(error, "Error cannot be null"));
        }

        /**
         * Check if the result is successful.
         *
         * @return true if successful, false otherwise
         */
        public boolean isSuccess() {
            return error == null;
        }

        /**
         * Check if the result has an error.
         *
         * @return true if has error, false otherwise
         */
        public boolean hasError() {
            return error != null;
        }
    }

    /**
     * Validate that a list of operations is not null or empty.
     *
     * @param operations The list to validate
     * @throws IllegalArgumentException If the list is null or empty
     */
    public static void validateOperations(List<byte[]> operations) {
        Objects.requireNonNull(operations, "Operations list cannot be null");
        if (operations.isEmpty()) {
            throw new IllegalArgumentException("Operations list cannot be empty");
        }
    }

    /**
     * Validate that priorities match the number of operations.
     *
     * @param operations The list of operations
     * @param priorities The list of priorities
     * @throws IllegalArgumentException If priorities don't match operations count
     */
    public static void validatePriorities(List<byte[]> operations, List<Byte> priorities) {
        Objects.requireNonNull(operations, "Operations list cannot be null");
        Objects.requireNonNull(priorities, "Priorities list cannot be null");
        if (priorities.size() != operations.size()) {
            throw new IllegalArgumentException("Priorities must match operations count");
        }
    }

    /**
     * Validate that a ByteBuffer is direct.
     *
     * @param buffer The buffer to validate
     * @throws IllegalArgumentException If the buffer is not direct
     */
    public static void validateDirectBuffer(ByteBuffer buffer) {
        Objects.requireNonNull(buffer, "Buffer cannot be null");
        if (!buffer.isDirect()) {
            throw new IllegalArgumentException("Buffer must be a direct ByteBuffer");
        }
    }

    /**
     * Get the native library loaded state.
     *
     * @return true if native library is loaded, false otherwise
     */
    public static boolean isNativeLibraryLoaded() {
        return nativeLibraryLoaded.get();
    }

    /**
     * Set the native library loaded state.
     *
     * @param loaded true if native library is loaded, false otherwise
     */
    public static void setNativeLibraryLoaded(boolean loaded) {
        nativeLibraryLoaded.set(loaded);
    }

    /**
     * Set the initialized state.
     *
     * @param initialized true if initialized, false otherwise
     */
    public static void setInitialized(boolean initialized) {
        RustPerformanceBase.initialized.set(initialized);
    }

    /**
     * Set the monitoring start time.
     *
     * @param startTime The start time in milliseconds
     */
    public static void setMonitoringStartTime(long startTime) {
        monitoringStartTime.set(startTime);
    }
}