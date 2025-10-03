package com.kneaf.core.protocol.core;

import java.util.Map;

/**
 * Exception for protocol-related errors with structured error information.
 */
public class ProtocolException extends Exception {
    
    private final int errorCode;
    private final String protocolFormat;
    private final String traceId;
    private final Map<String, Object> context;
    
    /**
     * Create a new protocol exception.
     * 
     * @param message the error message
     * @param errorCode the error code
     * @param protocolFormat the protocol format where the error occurred
     * @param traceId the trace ID for tracking
     */
    public ProtocolException(String message, int errorCode, String protocolFormat, String traceId) {
        super(message);
        this.errorCode = errorCode;
        this.protocolFormat = protocolFormat;
        this.traceId = traceId;
        this.context = Map.of();
    }
    
    /**
     * Create a new protocol exception with cause.
     * 
     * @param message the error message
     * @param cause the underlying cause
     * @param errorCode the error code
     * @param protocolFormat the protocol format where the error occurred
     * @param traceId the trace ID for tracking
     */
    public ProtocolException(String message, Throwable cause, int errorCode, String protocolFormat, String traceId) {
        super(message, cause);
        this.errorCode = errorCode;
        this.protocolFormat = protocolFormat;
        this.traceId = traceId;
        this.context = Map.of();
    }
    
    /**
     * Create a new protocol exception with context.
     * 
     * @param message the error message
     * @param errorCode the error code
     * @param protocolFormat the protocol format where the error occurred
     * @param traceId the trace ID for tracking
     * @param context additional context information
     */
    public ProtocolException(String message, int errorCode, String protocolFormat, String traceId, Map<String, Object> context) {
        super(message);
        this.errorCode = errorCode;
        this.protocolFormat = protocolFormat;
        this.traceId = traceId;
        this.context = Map.copyOf(context);
    }
    
    /**
     * Create a new protocol exception with cause and context.
     * 
     * @param message the error message
     * @param cause the underlying cause
     * @param errorCode the error code
     * @param protocolFormat the protocol format where the error occurred
     * @param traceId the trace ID for tracking
     * @param context additional context information
     */
    public ProtocolException(String message, Throwable cause, int errorCode, String protocolFormat, String traceId, Map<String, Object> context) {
        super(message, cause);
        this.errorCode = errorCode;
        this.protocolFormat = protocolFormat;
        this.traceId = traceId;
        this.context = Map.copyOf(context);
    }
    
    // Getters
    public int getErrorCode() {
        return errorCode;
    }
    
    public String getProtocolFormat() {
        return protocolFormat;
    }
    
    public String getTraceId() {
        return traceId;
    }
    
    public Map<String, Object> getContext() {
        return context;
    }
    
    /**
     * Create a validation failed exception.
     * 
     * @param message the error message
     * @param protocolFormat the protocol format
     * @param traceId the trace ID
     * @return validation exception
     */
    public static ProtocolException validationFailed(String message, String protocolFormat, String traceId) {
        return new ProtocolException(message, ProtocolConstants.ERROR_CODE_VALIDATION_FAILED, protocolFormat, traceId);
    }
    
    /**
     * Create an unsupported format exception.
     * 
     * @param format the unsupported format
     * @param traceId the trace ID
     * @return format exception
     */
    public static ProtocolException unsupportedFormat(String format, String traceId) {
        return new ProtocolException(
            "Unsupported protocol format: " + format,
            ProtocolConstants.ERROR_CODE_FORMAT_UNSUPPORTED,
            format,
            traceId
        );
    }
    
    /**
     * Create a version mismatch exception.
     * 
     * @param expectedVersion the expected version
     * @param actualVersion the actual version
     * @param traceId the trace ID
     * @return version exception
     */
    public static ProtocolException versionMismatch(String expectedVersion, String actualVersion, String traceId) {
        return new ProtocolException(
            String.format("Protocol version mismatch. Expected: %s, Actual: %s", expectedVersion, actualVersion),
            ProtocolConstants.ERROR_CODE_VERSION_MISMATCH,
            null,
            traceId
        );
    }
    
    /**
     * Create a size exceeded exception.
     * 
     * @param actualSize the actual size
     * @param maxSize the maximum allowed size
     * @param traceId the trace ID
     * @return size exception
     */
    public static ProtocolException sizeExceeded(int actualSize, int maxSize, String traceId) {
        return new ProtocolException(
            String.format("Data size exceeded limit. Size: %d, Max: %d", actualSize, maxSize),
            ProtocolConstants.ERROR_CODE_SIZE_EXCEEDED,
            null,
            traceId
        );
    }
    
    /**
     * Create an integrity check failed exception.
     * 
     * @param message the error message
     * @param traceId the trace ID
     * @return integrity exception
     */
    public static ProtocolException integrityFailed(String message, String traceId) {
        return new ProtocolException(message, ProtocolConstants.ERROR_CODE_INTEGRITY_FAILED, null, traceId);
    }
    
    /**
     * Create a timeout exception.
     * 
     * @param operation the operation that timed out
     * @param timeoutMs the timeout in milliseconds
     * @param traceId the trace ID
     * @return timeout exception
     */
    public static ProtocolException timeout(String operation, long timeoutMs, String traceId) {
        return new ProtocolException(
            String.format("Operation '%s' timed out after %d ms", operation, timeoutMs),
            ProtocolConstants.ERROR_CODE_TIMEOUT,
            null,
            traceId
        );
    }
    
    /**
     * Create a native call failed exception.
     * 
     * @param message the error message
     * @param cause the underlying cause
     * @param traceId the trace ID
     * @return native exception
     */
    public static ProtocolException nativeFailed(String message, Throwable cause, String traceId) {
        return new ProtocolException(message, cause, ProtocolConstants.ERROR_CODE_NATIVE_FAILED, null, traceId);
    }
    
    /**
     * Create a serialization failed exception.
     * 
     * @param message the error message
     * @param cause the underlying cause
     * @param protocolFormat the protocol format
     * @param traceId the trace ID
     * @return serialization exception
     */
    public static ProtocolException serializationFailed(String message, Throwable cause, String protocolFormat, String traceId) {
        return new ProtocolException(message, cause, ProtocolConstants.ERROR_CODE_SERIALIZATION_FAILED, protocolFormat, traceId);
    }
}