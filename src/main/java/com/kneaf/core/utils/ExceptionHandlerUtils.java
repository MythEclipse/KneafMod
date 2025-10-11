package com.kneaf.core.utils;

import org.slf4j.Logger;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Utility class for consistent exception handling patterns.
 * Provides reusable exception handling methods to eliminate DRY violations.
 */
public final class ExceptionHandlerUtils {

    private ExceptionHandlerUtils() {
        // Utility class - prevent instantiation
    }

    /**
     * Executes a task with exception handling and returns a default value on failure.
     *
     * @param task The task to execute
     * @param defaultValue The default value to return on failure
     * @param logger The logger to use for error logging
     * @param taskName The name of the task for logging purposes
     * @return The result of the task or defaultValue on failure
     */
    public static <T> T executeSafely(Supplier<T> task, T defaultValue, Logger logger, String taskName) {
        try {
            return task.get();
        } catch (Exception e) {
            logger.error("Failed to execute task '{}'", taskName, e);
            return defaultValue;
        }
    }

    /**
     * Executes a task with exception handling and returns a default value on failure.
     * Uses a generic error message.
     *
     * @param task The task to execute
     * @param defaultValue The default value to return on failure
     * @param logger The logger to use for error logging
     * @return The result of the task or defaultValue on failure
     */
    public static <T> T executeSafely(Supplier<T> task, T defaultValue, Logger logger) {
        try {
            return task.get();
        } catch (Exception e) {
            logger.error("Failed to execute task", e);
            return defaultValue;
        }
    }

    /**
     * Executes a task with exception handling and returns null on failure.
     *
     * @param task The task to execute
     * @param logger The logger to use for error logging
     * @param taskName The name of the task for logging purposes
     * @return The result of the task or null on failure
     */
    public static <T> T executeSafely(Supplier<T> task, Logger logger, String taskName) {
        return executeSafely(task, null, logger, taskName);
    }

    /**
     * Executes a task with exception handling and returns null on failure.
     * Uses a generic error message.
     *
     * @param task The task to execute
     * @param logger The logger to use for error logging
     * @return The result of the task or null on failure
     */
    public static <T> T executeSafely(Supplier<T> task, Logger logger) {
        return executeSafely(task, null, logger);
    }

    /**
     * Executes a void task with exception handling.
     *
     * @param task The task to execute
     * @param logger The logger to use for error logging
     * @param taskName The name of the task for logging purposes
     */
    public static void executeSafely(Runnable task, Logger logger, String taskName) {
        try {
            task.run();
        } catch (Exception e) {
            logger.error("Failed to execute task '{}'", taskName, e);
        }
    }

    /**
     * Executes a void task with exception handling.
     * Uses a generic error message.
     *
     * @param task The task to execute
     * @param logger The logger to use for error logging
     */
    public static void executeSafely(Runnable task, Logger logger) {
        try {
            task.run();
        } catch (Exception e) {
            logger.error("Failed to execute task", e);
        }
    }

    /**
     * Executes a callable task with exception handling and returns a default value on failure.
     *
     * @param task The callable task to execute
     * @param defaultValue The default value to return on failure
     * @param logger The logger to use for error logging
     * @param taskName The name of the task for logging purposes
     * @return The result of the task or defaultValue on failure
     */
    public static <T> T executeSafely(Callable<T> task, T defaultValue, Logger logger, String taskName) {
        try {
            return task.call();
        } catch (Exception e) {
            logger.error("Failed to execute callable task '{}'", taskName, e);
            return defaultValue;
        }
    }

    /**
     * Executes a callable task with exception handling and returns a default value on failure.
     * Uses a generic error message.
     *
     * @param task The callable task to execute
     * @param defaultValue The default value to return on failure
     * @param logger The logger to use for error logging
     * @return The result of the task or defaultValue on failure
     */
    public static <T> T executeSafely(Callable<T> task, T defaultValue, Logger logger) {
        try {
            return task.call();
        } catch (Exception e) {
            logger.error("Failed to execute callable task", e);
            return defaultValue;
        }
    }

    /**
     * Wraps an exception with a custom message and rethrows it.
     *
     * @param e The original exception
     * @param message The custom message
     * @param logger The logger to use for error logging
     * @param <T> The exception type
     * @throws T The rethrown exception
     */
    public static <T extends Exception> void wrapAndRethrow(Exception e, String message, Logger logger) throws T {
        logger.error(message, e);
        throw (T) e;
    }

    /**
     * Wraps an exception with a custom message and rethrows it as a RuntimeException.
     *
     * @param e The original exception
     * @param message The custom message
     * @param logger The logger to use for error logging
     */
    public static void wrapAndRethrowAsRuntime(Exception e, String message, Logger logger) {
        logger.error(message, e);
        throw new RuntimeException(message, e);
    }

    /**
     * Wraps an exception with a custom message and rethrows it as an IllegalStateException.
     *
     * @param e The original exception
     * @param message The custom message
     * @param logger The logger to use for error logging
     */
    public static void wrapAndRethrowAsIllegalState(Exception e, String message, Logger logger) {
        logger.error(message, e);
        throw new IllegalStateException(message, e);
    }

    /**
     * Wraps an exception with a custom message and rethrows it as an IllegalArgumentException.
     *
     * @param e The original exception
     * @param message The custom message
     * @param logger The logger to use for error logging
     */
    public static void wrapAndRethrowAsIllegalArgument(Exception e, String message, Logger logger) {
        logger.error(message, e);
        throw new IllegalArgumentException(message, e);
    }

    /**
     * Executes a task and returns a CompletableFuture that completes with the result or a default value on failure.
     *
     * @param task The task to execute
     * @param defaultValue The default value to complete with on failure
     * @param logger The logger to use for error logging
     * @param taskName The name of the task for logging purposes
     * @return A CompletableFuture that completes with the result or defaultValue
     */
    public static <T> CompletableFuture<T> executeSafelyAsync(Supplier<T> task, T defaultValue, Logger logger, String taskName) {
        return CompletableFuture.supplyAsync(() -> executeSafely(task, defaultValue, logger, taskName));
    }

    /**
     * Executes a task and returns a CompletableFuture that completes with the result or a default value on failure.
     * Uses a generic error message.
     *
     * @param task The task to execute
     * @param defaultValue The default value to complete with on failure
     * @param logger The logger to use for error logging
     * @return A CompletableFuture that completes with the result or defaultValue
     */
    public static <T> CompletableFuture<T> executeSafelyAsync(Supplier<T> task, T defaultValue, Logger logger) {
        return CompletableFuture.supplyAsync(() -> executeSafely(task, defaultValue, logger));
    }

    /**
     * Executes a void task asynchronously with exception handling.
     *
     * @param task The task to execute
     * @param logger The logger to use for error logging
     * @param taskName The name of the task for logging purposes
     * @return A CompletableFuture that completes when the task finishes
     */
    public static CompletableFuture<Void> executeSafelyAsync(Runnable task, Logger logger, String taskName) {
        return CompletableFuture.runAsync(() -> executeSafely(task, logger, taskName));
    }

    /**
     * Executes a void task asynchronously with exception handling.
     * Uses a generic error message.
     *
     * @param task The task to execute
     * @param logger The logger to use for error logging
     * @return A CompletableFuture that completes when the task finishes
     */
    public static CompletableFuture<Void> executeSafelyAsync(Runnable task, Logger logger) {
        return CompletableFuture.runAsync(() -> executeSafely(task, logger));
    }

    /**
     * Checks if an exception is recoverable (should be retried).
     *
     * @param e The exception to check
     * @return true if the exception is recoverable, false otherwise
     */
    public static boolean isRecoverable(Throwable e) {
        // Define recoverable exceptions - adjust as needed for your application
        return !(e instanceof OutOfMemoryError) &&
               !(e instanceof StackOverflowError) &&
               !(e instanceof VirtualMachineError) &&
               !(e instanceof ThreadDeath) &&
               !(e instanceof LinkageError);
    }

    /**
     * Checks if an exception should be logged as a warning instead of an error.
     *
     * @param e The exception to check
     * @return true if the exception should be logged as a warning, false otherwise
     */
    public static boolean shouldLogAsWarning(Throwable e) {
        // Define exceptions that should be logged as warnings - adjust as needed
        return e instanceof IllegalArgumentException ||
               e instanceof IllegalStateException ||
               e instanceof UnsupportedOperationException;
    }

    /**
     * Logs an exception with appropriate level based on its type.
     *
     * @param e The exception to log
     * @param logger The logger to use
     * @param context Additional context information for the log message
     */
    public static void logException(Throwable e, Logger logger, String context) {
        if (shouldLogAsWarning(e)) {
            logger.warn("{}: {}", context, e.getMessage());
        } else {
            logger.error("{}: {}", context, e.getMessage(), e);
        }
    }

    /**
     * Logs an exception with appropriate level based on its type.
     * Uses a generic context message.
     *
     * @param e The exception to log
     * @param logger The logger to use
     */
    public static void logException(Throwable e, Logger logger) {
        logException(e, logger, "Exception occurred");
    }
}