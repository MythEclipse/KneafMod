package com.kneaf.core.performance;

import com.kneaf.core.performance.error.RustPerformanceError;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Base class for Rust performance functionality using template method pattern.
 * Provides safe native call execution with centralized error handling.
 */
public class RustPerformanceBase {
    protected static final Logger LOGGER = Logger.getLogger(RustPerformanceBase.class.getName());
    
    /**
     * Template method for safe native calls.
     * Handles initialization check and error handling.
     *
     * @param callable The native method call to execute
     * @param errorCode The error code to use if the call fails
     * @param isInitialized Check if the system is initialized
     * @param <T> The return type of the native call
     * @return The result of the native call, or null if failed
     */
    public static <T> T safeNativeCall(Supplier<T> callable, RustPerformanceError errorCode, Supplier<Boolean> isInitialized) {
        return safeNativeCall(callable, errorCode, isInitialized, false);
    }
    
    /**
     * Template method for safe native calls with exception propagation option.
     * Handles initialization check and error handling.
     *
     * @param callable The native method call to execute
     * @param errorCode The error code to use if the call fails
     * @param isInitialized Check if the system is initialized
     * @param propagateExceptions Whether to propagate exceptions or log them
     * @param <T> The return type of the native call
     * @return The result of the native call, or null if failed
     * @throws RuntimeException If propagateExceptions is true and the call fails
     */
    public static <T> T safeNativeCall(Supplier<T> callable, RustPerformanceError errorCode, Supplier<Boolean> isInitialized, boolean propagateExceptions) {
        // Check if initialized
        if (!isInitialized.get()) {
            logError(errorCode, RustPerformanceError.NOT_INITIALIZED.getMessage());
            if (propagateExceptions) {
                throw new IllegalStateException(RustPerformanceError.NOT_INITIALIZED.getMessage());
            }
            return null;
        }
        
        try {
            return callable.get();
        } catch (Exception e) {
            logError(errorCode, e);
            if (propagateExceptions) {
                throw new RuntimeException(errorCode.getMessage(), e);
            }
            return null;
        }
    }
    
    /**
     * Template method for safe native calls that return void.
     * Handles initialization check and error handling.
     *
     * @param runnable The native method call to execute
     * @param errorCode The error code to use if the call fails
     * @param isInitialized Check if the system is initialized
     */
    public static void safeNativeVoidCall(Runnable runnable, RustPerformanceError errorCode, Supplier<Boolean> isInitialized) {
        safeNativeVoidCall(runnable, errorCode, isInitialized, false);
    }
    
    /**
     * Template method for safe native calls that return void with exception propagation option.
     * Handles initialization check and error handling.
     *
     * @param runnable The native method call to execute
     * @param errorCode The error code to use if the call fails
     * @param isInitialized Check if the system is initialized
     * @param propagateExceptions Whether to propagate exceptions or log them
     */
    public static void safeNativeVoidCall(Runnable runnable, RustPerformanceError errorCode, Supplier<Boolean> isInitialized, boolean propagateExceptions) {
        // Check if initialized
        if (!isInitialized.get()) {
            logError(errorCode, RustPerformanceError.NOT_INITIALIZED.getMessage());
            if (propagateExceptions) {
                throw new IllegalStateException(RustPerformanceError.NOT_INITIALIZED.getMessage());
            }
            return;
        }
        
        try {
            runnable.run();
        } catch (Exception e) {
            logError(errorCode, e);
            if (propagateExceptions) {
                throw new RuntimeException(errorCode.getMessage(), e);
            }
        }
    }
    
    /**
     * Template method for safe native calls that return boolean.
     * Handles initialization check and error handling with default false on failure.
     *
     * @param callable The native method call to execute
     * @param errorCode The error code to use if the call fails
     * @param isInitialized Check if the system is initialized
     * @return The result of the native call, or false if failed
     */
    public static boolean safeNativeBooleanCall(Supplier<Boolean> callable, RustPerformanceError errorCode, Supplier<Boolean> isInitialized) {
        return safeNativeBooleanCall(callable, errorCode, isInitialized, false);
    }
    
    /**
     * Template method for safe native calls that return boolean with exception propagation option.
     * Handles initialization check and error handling with default false on failure.
     *
     * @param callable The native method call to execute
     * @param errorCode The error code to use if the call fails
     * @param isInitialized Check if the system is initialized
     * @param propagateExceptions Whether to propagate exceptions or log them
     * @return The result of the native call, or false if failed
     * @throws RuntimeException If propagateExceptions is true and the call fails
     */
    public static boolean safeNativeBooleanCall(Supplier<Boolean> callable, RustPerformanceError errorCode, Supplier<Boolean> isInitialized, boolean propagateExceptions) {
        // Check if initialized
        if (!isInitialized.get()) {
            logError(errorCode, RustPerformanceError.NOT_INITIALIZED.getMessage());
            if (propagateExceptions) {
                throw new IllegalStateException(RustPerformanceError.NOT_INITIALIZED.getMessage());
            }
            return false;
        }
        
        try {
            return callable.get();
        } catch (Exception e) {
            logError(errorCode, e);
            if (propagateExceptions) {
                throw new RuntimeException(errorCode.getMessage(), e);
            }
            return false;
        }
    }
    
    /**
     * Log an error with the specified error code and message.
     *
     * @param errorCode The error code
     * @param message The error message
     */
    public static void logError(RustPerformanceError errorCode, String message) {
        LOGGER.log(Level.WARNING, "[" + errorCode.name() + "] " + message);
    }
    
    /**
     * Log an error with the specified error code and throwable.
     *
     * @param errorCode The error code
     * @param t The throwable
     */
    public static void logError(RustPerformanceError errorCode, Throwable t) {
        LOGGER.log(Level.WARNING, "[" + errorCode.name() + "] " + errorCode.getMessage(), t);
    }
}