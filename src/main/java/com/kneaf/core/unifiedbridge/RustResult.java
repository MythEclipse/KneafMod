package com.kneaf.core.unifiedbridge;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.concurrent.TimeUnit;

/**
 * Rust-inspired Result type that represents either success (Ok) or failure (Err).
 * Follows Rust's pattern of explicit error handling with type safety.
 *
 * @param <T> The type of successful result value
 * @param <E> The type of error value for failures
 */
public abstract sealed class RustResult<T, E extends Throwable>
        permits RustResult.Ok, RustResult.Err {

    // Prevent direct instantiation
    private RustResult() {}

    /**
     * Check if this is an Ok variant (success).
     * @return true if Ok, false if Err
     */
    public abstract boolean isOk();

    /**
     * Check if this is an Err variant (failure).
     * @return true if Err, false if Ok
     */
    public abstract boolean isErr();

    /**
     * Get the successful value if this is Ok, otherwise throw the error.
     * @return The successful value
     * @throws E The error if this is Err
     */
    public abstract T unwrap() throws E;

    /**
     * Get the successful value if this is Ok, otherwise return the provided default.
     * @param defaultValue The default value to return if this is Err
     * @return The successful value or defaultValue
     */
    public abstract T unwrapOr(T defaultValue);

    /**
     * Map a Result<T, E> to Result<U, E> by applying a function to the successful value.
     * @param mapper The function to apply to the successful value
     * @param <U> The new successful value type
     * @return Result<U, E>
     */
    public abstract <U> RustResult<U, E> map(Function<? super T, ? extends U> mapper);

    /**
     * Map a Result<T, E> to Result<T, F> by applying a function to the error value.
     * @param mapper The function to apply to the error value
     * @param <F> The new error type
     * @return Result<T, F>
     */
    public abstract <F extends Throwable> RustResult<T, F> mapErr(Function<? super E, ? extends F> mapper);

    /**
     * FlatMap a Result<T, E> to Result<U, E> by applying a function that returns a Result.
     * @param mapper The function to apply to the successful value
     * @param <U> The new successful value type
     * @return Result<U, E>
     */
    public abstract <U> RustResult<U, E> flatMap(Function<? super T, RustResult<U, E>> mapper);

    /**
     * Get an Optional containing the successful value if Ok, otherwise empty Optional.
     * @return Optional<T>
     */
    public abstract Optional<T> toOptional();

    /**
     * Get an Optional containing the error value if Err, otherwise empty Optional.
     * @return Optional<E>
     */
    public abstract Optional<E> toOptionalErr();

    /**
     * Ok variant - represents a successful result.
     * @param <T> The type of successful result value
     * @param <E> The type of error value (unused but maintained for type consistency)
     */
    public static final class Ok<T, E extends Throwable> extends RustResult<T, E> {
        private final T value;
        private final long taskId;
        private final long startTimeNanos;
        private final long endTimeNanos;
        private final Map<String, Object> metadata;

        public Ok(T value, long taskId, long startTimeNanos, long endTimeNanos, Map<String, Object> metadata) {
            this.value = Objects.requireNonNull(value);
            this.taskId = taskId;
            this.startTimeNanos = startTimeNanos;
            this.endTimeNanos = endTimeNanos;
            this.metadata = Map.copyOf(Objects.requireNonNull(metadata));
        }

        @Override
        public boolean isOk() {
            return true;
        }

        @Override
        public boolean isErr() {
            return false;
        }

        @Override
        public T unwrap() {
            return value;
        }

        @Override
        public T unwrapOr(T defaultValue) {
            return value;
        }

        @Override
        public <U> RustResult<U, E> map(Function<? super T, ? extends U> mapper) {
            return new Ok<>(mapper.apply(value), taskId, startTimeNanos, endTimeNanos, metadata);
        }

        @Override
        public <F extends Throwable> RustResult<T, F> mapErr(Function<? super E, ? extends F> mapper) {
            RustResult<T, F> result = (RustResult<T, F>) this;
            return result;
        }

        @Override
        public <U> RustResult<U, E> flatMap(Function<? super T, RustResult<U, E>> mapper) {
            return mapper.apply(value);
        }

        @Override
        public Optional<T> toOptional() {
            return Optional.of(value);
        }

        @Override
        public Optional<E> toOptionalErr() {
            return Optional.empty();
        }

        public T get() {
            return value;
        }

        public long getTaskId() {
            return taskId;
        }

        public long getStartTimeNanos() {
            return startTimeNanos;
        }

        public long getEndTimeNanos() {
            return endTimeNanos;
        }

        public long getDurationNanos() {
            return endTimeNanos - startTimeNanos;
        }

        public long getDurationMillis() {
            return TimeUnit.NANOSECONDS.toMillis(getDurationNanos());
        }

        public Map<String, Object> getMetadata() {
            return metadata;
        }

        public String getMessage() {
            Object message = metadata.get("message");
            return message != null ? message.toString() : null;
        }

        @Override
        public String toString() {
            return "Ok{" +
                    "value=" + value +
                    ", taskId=" + taskId +
                    ", durationMillis=" + getDurationMillis() +
                    '}';
        }
    }

    /**
     * Err variant - represents a failed result with an error.
     * @param <T> The type of successful result value (unused but maintained for type consistency)
     * @param <E> The type of error value
     */
    public static final class Err<T, E extends Throwable> extends RustResult<T, E> {
        private final E error;
        private final long taskId;
        private final long startTimeNanos;
        private final long endTimeNanos;
        private final Map<String, Object> metadata;

        public Err(E error, long taskId, long startTimeNanos, long endTimeNanos, Map<String, Object> metadata) {
            this.error = Objects.requireNonNull(error);
            this.taskId = taskId;
            this.startTimeNanos = startTimeNanos;
            this.endTimeNanos = endTimeNanos;
            this.metadata = Map.copyOf(Objects.requireNonNull(metadata));
        }

        @Override
        public boolean isOk() {
            return false;
        }

        @Override
        public boolean isErr() {
            return true;
        }

        @Override
        public T unwrap() throws E {
            throw error;
        }

        @Override
        public T unwrapOr(T defaultValue) {
            return defaultValue;
        }

        @Override
        public <U> RustResult<U, E> map(Function<? super T, ? extends U> mapper) {
            RustResult<U, E> result = (RustResult<U, E>) this;
            return result;
        }

        @Override
        public <F extends Throwable> RustResult<T, F> mapErr(Function<? super E, ? extends F> mapper) {
            return new Err<>(mapper.apply(error), taskId, startTimeNanos, endTimeNanos, metadata);
        }

        @Override
        public <U> RustResult<U, E> flatMap(Function<? super T, RustResult<U, E>> mapper) {
            RustResult<U, E> result = (RustResult<U, E>) this;
            return result;
        }

        @Override
        public Optional<T> toOptional() {
            return Optional.empty();
        }

        @Override
        public Optional<E> toOptionalErr() {
            return Optional.of(error);
        }

        public E getError() {
            return error;
        }

        public long getTaskId() {
            return taskId;
        }

        public long getStartTimeNanos() {
            return startTimeNanos;
        }

        public long getEndTimeNanos() {
            return endTimeNanos;
        }

        public long getDurationNanos() {
            return endTimeNanos - startTimeNanos;
        }

        public long getDurationMillis() {
            return TimeUnit.NANOSECONDS.toMillis(getDurationNanos());
        }

        public Map<String, Object> getMetadata() {
            return metadata;
        }

        public String getMessage() {
            Object message = metadata.get("message");
            return message != null ? message.toString() : null;
        }

        @Override
        public String toString() {
            return "Err{" +
                    "error=" + error +
                    ", taskId=" + taskId +
                    ", durationMillis=" + getDurationMillis() +
                    '}';
        }
    }

    /**
     * Factory methods for creating RustResult instances.
     */
    public static class Factory {
        private Factory() {}

        public static <T, E extends Throwable> Ok<T, E> ok(T value, Map<String, Object> metadata) {
            long now = System.nanoTime();
            return new Ok<>(value, now, now, now + calculateProcessingTime(value), metadata);
        }

        public static <T, E extends Throwable> Ok<T, E> ok(T value) {
            return ok(value, Map.of());
        }

        public static <T, E extends Throwable> Err<T, E> err(E error, Map<String, Object> metadata) {
            long now = System.nanoTime();
            return new Err<>(error, now, now, now + calculateProcessingTime(error), metadata);
        }

        public static <T, E extends Throwable> Err<T, E> err(E error) {
            return err(error, Map.of());
        }

        public static <T> RustResult<T, BridgeException> fromBridgeResult(BridgeResult bridgeResult) {
            if (bridgeResult.isSuccess()) {
                try {
                    T value = convertToType((byte[]) bridgeResult.getResultData(), bridgeResult.getMetadata());
                    return ok(value, bridgeResult.getMetadata());
                } catch (Exception e) {
                    return err(new BridgeException("Failed to convert result data", e), bridgeResult.getMetadata());
                }
            } else {
                return err(new BridgeException(bridgeResult.getErrorMessage()), bridgeResult.getMetadata());
            }
        }

        private static <T> T convertToType(byte[] resultData, Map<String, Object> metadata) {
            if (resultData == null) {
                return null;
            }

            try {
                String str = new String(resultData, "UTF-8");
                if (str.trim().matches("-?\\d+(\\.\\d+)?")) {
                    if (str.contains(".")) {
                        return (T) Double.valueOf(str.trim());
                    } else if (str.length() <= 10) {
                        return (T) Integer.valueOf(str.trim());
                    } else {
                        return (T) Long.valueOf(str.trim());
                    }
                } else if (str.trim().matches("true|false|TRUE|FALSE|1|0")) {
                    return (T) Boolean.valueOf(str.trim().toLowerCase());
                } else {
                    return (T) str;
                }
            } catch (Exception e) {
                return (T) resultData.clone();
            }
        }

        private static long calculateProcessingTime(Object value) {
            if (value == null) return 1000;
            if (value instanceof String str) return str.length() * 10;
            if (value instanceof byte[]) return ((byte[]) value).length * 5;
            return 5000;
        }
    }
}