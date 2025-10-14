package com.kneaf.core.unifiedbridge;

import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Full implementation of BridgeResult with serialization support.
 * Handles native operation results with metadata, error handling, and cross-platform serialization.
 */
public class BridgeResult implements Serializable {
    private static final long serialVersionUID = 1L;

    // Result status
    private final boolean success;
    private final ResultType resultType;

    // Result data - can be primitive, object, or byte array
    private final Object resultData;
    private final byte[] binaryData;

    // Metadata and context
    private final Map<String, Object> metadata;
    private final String operationId;
    private final long executionTimeMs;
    private final long timestamp;

    // Error information
    private final String errorMessage;
    private final String errorCode;
    private final Throwable cause;

    // Performance metrics
    private final long nativeMemoryUsed;
    private final int threadsUsed;

    /**
     * Result type enumeration for type-safe handling.
     */
    public enum ResultType {
        OBJECT,      // Java object result
        BINARY,      // Raw binary data
        PRIMITIVE,   // Primitive type (int, long, double, etc.)
        VOID,        // No result data
        ERROR        // Error result
    }

    /**
     * Private constructor - use factory methods.
     */
    private BridgeResult(boolean success, ResultType resultType, Object resultData,
                        byte[] binaryData, Map<String, Object> metadata, String operationId,
                        long executionTimeMs, String errorMessage, String errorCode,
                        Throwable cause, long nativeMemoryUsed, int threadsUsed) {
        this.success = success;
        this.resultType = resultType;
        this.resultData = resultData;
        this.binaryData = binaryData != null ? binaryData.clone() : null;
        this.metadata = metadata != null ? new ConcurrentHashMap<>(metadata) : new ConcurrentHashMap<>();
        this.operationId = operationId;
        this.executionTimeMs = executionTimeMs;
        this.timestamp = System.currentTimeMillis();
        this.errorMessage = errorMessage;
        this.errorCode = errorCode;
        this.cause = cause;
        this.nativeMemoryUsed = nativeMemoryUsed;
        this.threadsUsed = threadsUsed;
    }

    /**
     * Create a successful result with object data.
     */
    public static BridgeResult success(Object resultData) {
        return new BridgeResult(true, ResultType.OBJECT, resultData, null,
                              null, null, 0, null, null, null, 0, 0);
    }

    /**
     * Create a successful result with binary data.
     */
    public static BridgeResult success(byte[] binaryData) {
        return new BridgeResult(true, ResultType.BINARY, null, binaryData,
                              null, null, 0, null, null, null, 0, 0);
    }

    /**
     * Create a successful result with primitive data.
     */
    public static BridgeResult success(Object primitiveData, long executionTimeMs) {
        return new BridgeResult(true, ResultType.PRIMITIVE, primitiveData, null,
                              null, null, executionTimeMs, null, null, null, 0, 0);
    }

    /**
     * Create a successful void result.
     */
    public static BridgeResult success() {
        return new BridgeResult(true, ResultType.VOID, null, null,
                              null, null, 0, null, null, null, 0, 0);
    }

    /**
     * Create a successful result with full metadata.
     */
    public static BridgeResult success(Object resultData, Map<String, Object> metadata,
                                     String operationId, long executionTimeMs,
                                     long nativeMemoryUsed, int threadsUsed) {
        return new BridgeResult(true, ResultType.OBJECT, resultData, null, metadata,
                              operationId, executionTimeMs, null, null, null,
                              nativeMemoryUsed, threadsUsed);
    }

    /**
     * Create an error result.
     */
    public static BridgeResult error(String errorMessage) {
        return new BridgeResult(false, ResultType.ERROR, null, null, null,
                              null, 0, errorMessage, null, null, 0, 0);
    }

    /**
     * Create an error result with exception.
     */
    public static BridgeResult error(String errorMessage, Throwable cause) {
        return new BridgeResult(false, ResultType.ERROR, null, null, null,
                              null, 0, errorMessage, null, cause, 0, 0);
    }

    /**
     * Create an error result with error code.
     */
    public static BridgeResult error(String errorCode, String errorMessage, Throwable cause) {
        return new BridgeResult(false, ResultType.ERROR, null, null, null,
                              null, 0, errorMessage, errorCode, cause, 0, 0);
    }

    // Getters
    public boolean isSuccess() { return success; }
    public ResultType getResultType() { return resultType; }
    public Object getResultData() { return resultData; }
    public byte[] getBinaryData() { return binaryData != null ? binaryData.clone() : null; }
    public Map<String, Object> getMetadata() { return new HashMap<>(metadata); }
    public String getOperationId() { return operationId; }
    public long getExecutionTimeMs() { return executionTimeMs; }
    public long getTimestamp() { return timestamp; }
    public String getErrorMessage() { return errorMessage; }
    public String getErrorCode() { return errorCode; }
    public Throwable getCause() { return cause; }
    public long getNativeMemoryUsed() { return nativeMemoryUsed; }
    public int getThreadsUsed() { return threadsUsed; }

    /**
     * Get result data cast to specific type.
     */
    public <T> T getResultData(Class<T> type) {
        if (resultData == null) {
            return null;
        }
        if (type.isInstance(resultData)) {
            return (T) resultData;
        }
        throw new ClassCastException("Cannot cast result data to " + type.getName());
    }

    /**
     * Check if result has specific metadata key.
     */
    public boolean hasMetadata(String key) {
        return metadata.containsKey(key);
    }

    /**
     * Get metadata value with type safety.
     */
    public <T> T getMetadata(String key, Class<T> type) {
        Object value = metadata.get(key);
        if (value == null) {
            return null;
        }
        if (type.isInstance(value)) {
            return (T) value;
        }
        throw new ClassCastException("Cannot cast metadata value for key '" + key + "' to " + type.getName());
    }

    /**
     * Serialize this result to byte array.
     */
    public byte[] toByteArray() throws IOException {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             ObjectOutputStream oos = new ObjectOutputStream(baos)) {
            oos.writeObject(this);
            return baos.toByteArray();
        }
    }

    /**
     * Deserialize result from byte array.
     */
    public static BridgeResult fromByteArray(byte[] data) throws IOException, ClassNotFoundException {
        try (ByteArrayInputStream bais = new ByteArrayInputStream(data);
             ObjectInputStream ois = new ObjectInputStream(bais)) {
            return (BridgeResult) ois.readObject();
        }
    }

    /**
     * Create a copy of this result with additional metadata.
     */
    public BridgeResult withMetadata(String key, Object value) {
        Map<String, Object> newMetadata = new HashMap<>(metadata);
        newMetadata.put(key, value);
        return new BridgeResult(success, resultType, resultData, binaryData, newMetadata,
                              operationId, executionTimeMs, errorMessage, errorCode, cause,
                              nativeMemoryUsed, threadsUsed);
    }

    /**
     * Create a copy of this result with updated execution time.
     */
    public BridgeResult withExecutionTime(long executionTimeMs) {
        return new BridgeResult(success, resultType, resultData, binaryData, metadata,
                              operationId, executionTimeMs, errorMessage, errorCode, cause,
                              nativeMemoryUsed, threadsUsed);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("BridgeResult{success=").append(success);
        sb.append(", type=").append(resultType);
        if (operationId != null) {
            sb.append(", operationId='").append(operationId).append('\'');
        }
        if (executionTimeMs > 0) {
            sb.append(", executionTime=").append(executionTimeMs).append("ms");
        }
        if (nativeMemoryUsed > 0) {
            sb.append(", memory=").append(nativeMemoryUsed).append(" bytes");
        }
        if (threadsUsed > 0) {
            sb.append(", threads=").append(threadsUsed);
        }
        if (!success && errorMessage != null) {
            sb.append(", error='").append(errorMessage).append('\'');
        }
        sb.append('}');
        return sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BridgeResult that = (BridgeResult) o;
        return success == that.success &&
               executionTimeMs == that.executionTimeMs &&
               nativeMemoryUsed == that.nativeMemoryUsed &&
               threadsUsed == that.threadsUsed &&
               resultType == that.resultType &&
               Objects.equals(resultData, that.resultData) &&
               Arrays.equals(binaryData, that.binaryData) &&
               Objects.equals(metadata, that.metadata) &&
               Objects.equals(operationId, that.operationId) &&
               Objects.equals(errorMessage, that.errorMessage) &&
               Objects.equals(errorCode, that.errorCode);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(success, resultType, resultData, metadata, operationId,
                                executionTimeMs, errorMessage, errorCode, nativeMemoryUsed, threadsUsed);
        result = 31 * result + Arrays.hashCode(binaryData);
        return result;
    }
}