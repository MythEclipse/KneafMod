package com.kneaf.core.utils;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Utility class for common asynchronous operations.
 * Provides reusable async methods to eliminate DRY violations.
 */
public final class AsyncUtils {

    private AsyncUtils() {
        // Utility class - prevent instantiation
    }

    /**
     * Executes a task asynchronously with a default executor.
     *
     * @param task The task to execute
     * @return A CompletableFuture that completes with the result
     */
    public static <T> CompletableFuture<T> runAsync(Supplier<T> task) {
        return CompletableFuture.supplyAsync(task);
    }

    /**
     * Executes a task asynchronously with a custom executor.
     *
     * @param task The task to execute
     * @param executor The executor to use
     * @return A CompletableFuture that completes with the result
     */
    public static <T> CompletableFuture<T> runAsync(Supplier<T> task, Executor executor) {
        return CompletableFuture.supplyAsync(task, executor);
    }

    /**
     * Executes a void task asynchronously with a default executor.
     *
     * @param task The task to execute
     * @return A CompletableFuture that completes when the task finishes
     */
    public static CompletableFuture<Void> runAsync(Runnable task) {
        return CompletableFuture.runAsync(task);
    }

    /**
     * Executes a void task asynchronously with a custom executor.
     *
     * @param task The task to execute
     * @param executor The executor to use
     * @return A CompletableFuture that completes when the task finishes
     */
    public static CompletableFuture<Void> runAsync(Runnable task, Executor executor) {
        return CompletableFuture.runAsync(task, executor);
    }

    /**
     * Executes a task with a timeout.
     *
     * @param task The task to execute
     * @param timeout The timeout value
     * @param timeUnit The time unit of the timeout
     * @param defaultValue The value to return if the timeout is exceeded
     * @return A CompletableFuture that completes with the result or defaultValue on timeout
     */
    public static <T> CompletableFuture<T> withTimeout(Supplier<T> task, long timeout, TimeUnit timeUnit, T defaultValue) {
        return runAsync(task)
                .orTimeout(timeout, timeUnit)
                .exceptionally(e -> defaultValue);
    }

    /**
     * Executes a task with a timeout and custom executor.
     *
     * @param task The task to execute
     * @param executor The executor to use
     * @param timeout The timeout value
     * @param timeUnit The time unit of the timeout
     * @param defaultValue The value to return if the timeout is exceeded
     * @return A CompletableFuture that completes with the result or defaultValue on timeout
     */
    public static <T> CompletableFuture<T> withTimeout(Supplier<T> task, Executor executor, long timeout, TimeUnit timeUnit, T defaultValue) {
        return runAsync(task, executor)
                .orTimeout(timeout, timeUnit)
                .exceptionally(e -> defaultValue);
    }

    /**
     * Executes a void task with a timeout.
     *
     * @param task The task to execute
     * @param timeout The timeout value
     * @param timeUnit The time unit of the timeout
     * @return A CompletableFuture that completes when the task finishes or times out
     */
    public static CompletableFuture<Void> withTimeout(Runnable task, long timeout, TimeUnit timeUnit) {
        return runAsync(task)
                .orTimeout(timeout, timeUnit);
    }

    /**
     * Executes a void task with a timeout and custom executor.
     *
     * @param task The task to execute
     * @param executor The executor to use
     * @param timeout The timeout value
     * @param timeUnit The time unit of the timeout
     * @return A CompletableFuture that completes when the task finishes or times out
     */
    public static CompletableFuture<Void> withTimeout(Runnable task, Executor executor, long timeout, TimeUnit timeUnit) {
        return runAsync(task, executor)
                .orTimeout(timeout, timeUnit);
    }

    /**
     * Executes multiple tasks in parallel and waits for all to complete.
     *
     * @param tasks The tasks to execute
     * @return A CompletableFuture that completes when all tasks finish
     */
    @SafeVarargs
    public static CompletableFuture<Void> allOf(Runnable... tasks) {
        CompletableFuture<?>[] futures = new CompletableFuture[tasks.length];
        for (int i = 0; i < tasks.length; i++) {
            futures[i] = runAsync(tasks[i]);
        }
        return CompletableFuture.allOf(futures);
    }

    /**
     * Executes multiple tasks in parallel with a custom executor and waits for all to complete.
     *
     * @param executor The executor to use
     * @param tasks The tasks to execute
     * @return A CompletableFuture that completes when all tasks finish
     */
    @SafeVarargs
    public static CompletableFuture<Void> allOf(Executor executor, Runnable... tasks) {
        CompletableFuture<?>[] futures = new CompletableFuture[tasks.length];
        for (int i = 0; i < tasks.length; i++) {
            futures[i] = runAsync(tasks[i], executor);
        }
        return CompletableFuture.allOf(futures);
    }

    /**
     * Executes multiple tasks in parallel and returns the first completed result.
     *
     * @param tasks The tasks to execute
     * @return A CompletableFuture that completes with the first result
     */
    @SafeVarargs
    public static <T> CompletableFuture<T> anyOf(Supplier<T>... tasks) {
        CompletableFuture<T>[] futures = new CompletableFuture[tasks.length];
        for (int i = 0; i < tasks.length; i++) {
            futures[i] = runAsync(tasks[i]);
        }
        return CompletableFuture.anyOf(futures).thenApply(result -> (T) result);
    }

    /**
     * Executes multiple tasks in parallel with a custom executor and returns the first completed result.
     *
     * @param executor The executor to use
     * @param tasks The tasks to execute
     * @return A CompletableFuture that completes with the first result
     */
    @SafeVarargs
    public static <T> CompletableFuture<T> anyOf(Executor executor, Supplier<T>... tasks) {
        CompletableFuture<T>[] futures = new CompletableFuture[tasks.length];
        for (int i = 0; i < tasks.length; i++) {
            futures[i] = runAsync(tasks[i], executor);
        }
        return CompletableFuture.anyOf(futures).thenApply(result -> (T) result);
    }

    /**
     * Retries a task a specified number of times with exponential backoff.
     *
     * @param task The task to retry
     * @param maxRetries The maximum number of retries
     * @param initialDelay The initial delay between retries
     * @param maxDelay The maximum delay between retries
     * @param timeUnit The time unit for delays
     * @param scheduler The scheduler to use for delays
     * @return A CompletableFuture that completes with the result or fails after max retries
     */
    public static <T> CompletableFuture<T> retryWithBackoff(
            Supplier<T> task, 
            int maxRetries, 
            long initialDelay, 
            long maxDelay, 
            TimeUnit timeUnit,
            ScheduledExecutorService scheduler) {
        
        return CompletableFuture.supplyAsync(() -> {
            int retries = 0;
            long delay = initialDelay;
            
            while (true) {
                try {
                    return task.get();
                } catch (Exception e) {
                    retries++;
                    if (retries > maxRetries) {
                        throw new RuntimeException("Task failed after " + maxRetries + " retries", e);
                    }
                    
                    // Exponential backoff with maximum limit
                    delay = Math.min(delay * 2, maxDelay);
                    
                    try {
                        Thread.sleep(timeUnit.toMillis(delay));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Task interrupted during retry", ie);
                    }
                }
            }
        });
    }

    /**
     * Creates a CompletableFuture that completes after a specified delay.
     *
     * @param delay The delay value
     * @param timeUnit The time unit of the delay
     * @param scheduler The scheduler to use for the delay
     * @return A CompletableFuture that completes after the delay
     */
    public static CompletableFuture<Void> delay(long delay, TimeUnit timeUnit, ScheduledExecutorService scheduler) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        scheduler.schedule(() -> future.complete(null), delay, timeUnit);
        return future;
    }

    /**
     * Transforms the result of a CompletableFuture using a function.
     *
     * @param future The future to transform
     * @param transformer The transformation function
     * @return A new CompletableFuture with the transformed result
     */
    public static <T, R> CompletableFuture<R> transform(CompletableFuture<T> future, java.util.function.Function<T, R> transformer) {
        return future.thenApply(transformer);
    }

    /**
     * Transforms the result of a CompletableFuture asynchronously using a function.
     *
     * @param future The future to transform
     * @param transformer The asynchronous transformation function
     * @return A new CompletableFuture with the transformed result
     */
    public static <T, R> CompletableFuture<R> transformAsync(CompletableFuture<T> future, java.util.function.Function<T, CompletableFuture<R>> transformer) {
        return future.thenCompose(transformer);
    }

    /**
     * Handles both success and failure cases of a CompletableFuture.
     *
     * @param future The future to handle
     * @param successHandler The function to handle successful results
     * @param failureHandler The function to handle failures
     * @return A new CompletableFuture that handles both cases
     */
    public static <T, R> CompletableFuture<R> handle(
            CompletableFuture<T> future,
            java.util.function.Function<T, R> successHandler,
            java.util.function.Function<Throwable, R> failureHandler) {
        
        return future.handle((result, error) -> {
            if (error != null) {
                return failureHandler.apply(error);
            } else {
                return successHandler.apply(result);
            }
        });
    }

    /**
     * Safely shuts down an executor service with a timeout.
     *
     * @param executor The executor service to shut down
     * @param timeout The timeout value
     * @param timeUnit The time unit of the timeout
     * @return true if shutdown was successful, false if timeout occurred
     */
    public static boolean shutdownExecutor(ExecutorService executor, long timeout, TimeUnit timeUnit) {
        executor.shutdown();
        try {
            return executor.awaitTermination(timeout, timeUnit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
            return false;
        }
    }

    /**
     * Safely shuts down an executor service immediately.
     *
     * @param executor The executor service to shut down
     */
    public static void shutdownExecutorNow(ExecutorService executor) {
        executor.shutdownNow();
    }

    /**
     * Creates a CompletableFuture that completes with a value after a specified delay.
     *
     * @param value The value to complete with
     * @param delay The delay value
     * @param timeUnit The time unit of the delay
     * @param scheduler The scheduler to use for the delay
     * @return A CompletableFuture that completes with the value after the delay
     */
    public static <T> CompletableFuture<T> delayedValue(T value, long delay, TimeUnit timeUnit, ScheduledExecutorService scheduler) {
        CompletableFuture<T> future = new CompletableFuture<>();
        scheduler.schedule(() -> future.complete(value), delay, timeUnit);
        return future;
    }

    /**
     * Creates a CompletableFuture that completes with an exception after a specified delay.
     *
     * @param exception The exception to complete with
     * @param delay The delay value
     * @param timeUnit The time unit of the delay
     * @param scheduler The scheduler to use for the delay
     * @return A CompletableFuture that completes with the exception after the delay
     */
    public static <T> CompletableFuture<T> delayedException(Throwable exception, long delay, TimeUnit timeUnit, ScheduledExecutorService scheduler) {
        CompletableFuture<T> future = new CompletableFuture<>();
        scheduler.schedule(() -> future.completeExceptionally(exception), delay, timeUnit);
        return future;
    }
}