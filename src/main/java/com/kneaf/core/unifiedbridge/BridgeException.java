package com.kneaf.core.unifiedbridge;

import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Full implementation of BridgeException with serialization support.
 * Provides detailed error information, error codes, and cross-platform compatibility.
 */
public class BridgeException extends Exception {
    private static final long serialVersionUID = 1L;

    // Error classification
    private final ErrorCode errorCode;
    private final ErrorSeverity severity;

    // Context information
    private final String operationId;
    private final Map<String, Object> contextData;
    private final long timestamp;

    // Native error information
    private final int nativeErrorCode;
    private final String nativeErrorMessage;

    // Recovery information
    private final boolean recoverable;
    private final String recoverySuggestion;

    /**
     * Error code enumeration for standardized error handling.
     */
    public enum ErrorCode {
        // Connection errors
        CONNECTION_FAILED("BRIDGE_001", "Failed to establish connection to native bridge"),
        CONNECTION_LOST("BRIDGE_002", "Connection to native bridge was lost"),
        CONNECTION_TIMEOUT("BRIDGE_003", "Connection operation timed out"),

        // Library errors
        LIBRARY_NOT_FOUND("BRIDGE_004", "Native library not found"),
        LIBRARY_LOAD_FAILED("BRIDGE_005", "Failed to load native library"),
        LIBRARY_VERSION_MISMATCH("BRIDGE_006", "Native library version mismatch"),

        // Operation errors
        OPERATION_FAILED("BRIDGE_007", "Native operation failed"),
        OPERATION_TIMEOUT("BRIDGE_008", "Operation timed out"),
        OPERATION_CANCELLED("BRIDGE_009", "Operation was cancelled"),

        // Data errors
        INVALID_DATA("BRIDGE_010", "Invalid data provided to operation"),
        DATA_SERIALIZATION_FAILED("BRIDGE_011", "Failed to serialize data"),
        DATA_DESERIALIZATION_FAILED("BRIDGE_012", "Failed to deserialize data"),

        // Resource errors
        OUT_OF_MEMORY("BRIDGE_013", "Native memory allocation failed"),
        RESOURCE_EXHAUSTED("BRIDGE_014", "Native resources exhausted"),
        THREAD_CREATION_FAILED("BRIDGE_015", "Failed to create native thread"),

        // Configuration errors
        INVALID_CONFIGURATION("BRIDGE_016", "Invalid bridge configuration"),
        CONFIGURATION_LOAD_FAILED("BRIDGE_017", "Failed to load configuration"),

        // Health check errors
        HEALTH_CHECK_FAILED("BRIDGE_018", "Bridge health check failed"),
        HEALTH_CHECK_TIMEOUT("BRIDGE_019", "Health check timed out"),

        // Generic errors
        UNKNOWN_ERROR("BRIDGE_999", "Unknown bridge error");

        private final String code;
        private final String defaultMessage;

        ErrorCode(String code, String defaultMessage) {
            this.code = code;
            this.defaultMessage = defaultMessage;
        }

        public String getCode() { return code; }
        public String getDefaultMessage() { return defaultMessage; }
    }

    /**
     * Error severity levels.
     */
    public enum ErrorSeverity {
        LOW,      // Minor issue, operation can continue
        MEDIUM,   // Significant issue, may affect performance
        HIGH,     // Critical issue, operation failed but recoverable
        CRITICAL  // System-level failure, not recoverable
    }

    /**
     * Create a BridgeException with error code.
     */
    public BridgeException(ErrorCode errorCode) {
        super(errorCode.getDefaultMessage());
        this.errorCode = errorCode;
        this.severity = ErrorSeverity.MEDIUM;
        this.operationId = null;
        this.contextData = new ConcurrentHashMap<>();
        this.timestamp = System.currentTimeMillis();
        this.nativeErrorCode = 0;
        this.nativeErrorMessage = null;
        this.recoverable = true;
        this.recoverySuggestion = null;
    }

    /**
     * Create a BridgeException with custom message.
     */
    public BridgeException(String message) {
        super(message);
        this.errorCode = ErrorCode.UNKNOWN_ERROR;
        this.severity = ErrorSeverity.MEDIUM;
        this.operationId = null;
        this.contextData = new ConcurrentHashMap<>();
        this.timestamp = System.currentTimeMillis();
        this.nativeErrorCode = 0;
        this.nativeErrorMessage = null;
        this.recoverable = true;
        this.recoverySuggestion = null;
    }

    /**
     * Create a BridgeException with message and cause.
     */
    public BridgeException(String message, Throwable cause) {
        super(message, cause);
        this.errorCode = ErrorCode.UNKNOWN_ERROR;
        this.severity = ErrorSeverity.MEDIUM;
        this.operationId = null;
        this.contextData = new ConcurrentHashMap<>();
        this.timestamp = System.currentTimeMillis();
        this.nativeErrorCode = 0;
        this.nativeErrorMessage = null;
        this.recoverable = true;
        this.recoverySuggestion = null;
    }

    /**
     * Create a BridgeException with error code and custom message.
     */
    public BridgeException(ErrorCode errorCode, String message) {
        super(message != null ? message : errorCode.getDefaultMessage());
        this.errorCode = errorCode;
        this.severity = ErrorSeverity.MEDIUM;
        this.operationId = null;
        this.contextData = new ConcurrentHashMap<>();
        this.timestamp = System.currentTimeMillis();
        this.nativeErrorCode = 0;
        this.nativeErrorMessage = null;
        this.recoverable = true;
        this.recoverySuggestion = null;
    }

    /**
     * Create a BridgeException with error code, message, and cause.
     */
    public BridgeException(ErrorCode errorCode, String message, Throwable cause) {
        super(message != null ? message : errorCode.getDefaultMessage(), cause);
        this.errorCode = errorCode;
        this.severity = ErrorSeverity.MEDIUM;
        this.operationId = null;
        this.contextData = new ConcurrentHashMap<>();
        this.timestamp = System.currentTimeMillis();
        this.nativeErrorCode = 0;
        this.nativeErrorMessage = null;
        this.recoverable = true;
        this.recoverySuggestion = null;
    }

    /**
     * Create a fully configured BridgeException.
     */
    public BridgeException(ErrorCode errorCode, ErrorSeverity severity, String message,
                          String operationId, Map<String, Object> contextData,
                          int nativeErrorCode, String nativeErrorMessage,
                          boolean recoverable, String recoverySuggestion, Throwable cause) {
        super(message != null ? message : errorCode.getDefaultMessage(), cause);
        this.errorCode = errorCode;
        this.severity = severity;
        this.operationId = operationId;
        this.contextData = contextData != null ? new ConcurrentHashMap<>(contextData) : new ConcurrentHashMap<>();
        this.timestamp = System.currentTimeMillis();
        this.nativeErrorCode = nativeErrorCode;
        this.nativeErrorMessage = nativeErrorMessage;
        this.recoverable = recoverable;
        this.recoverySuggestion = recoverySuggestion;
    }

    // Getters
    public ErrorCode getErrorCode() { return errorCode; }
    public ErrorSeverity getSeverity() { return severity; }
    public String getOperationId() { return operationId; }
    public Map<String, Object> getContextData() { return new HashMap<>(contextData); }
    public long getTimestamp() { return timestamp; }
    public int getNativeErrorCode() { return nativeErrorCode; }
    public String getNativeErrorMessage() { return nativeErrorMessage; }
    public boolean isRecoverable() { return recoverable; }
    public String getRecoverySuggestion() { return recoverySuggestion; }

    /**
     * Add context data to this exception.
     */
    public BridgeException withContext(String key, Object value) {
        contextData.put(key, value);
        return this;
    }

    /**
     * Add multiple context data entries.
     */
    public BridgeException withContext(Map<String, Object> context) {
        if (context != null) {
            contextData.putAll(context);
        }
        return this;
    }

    /**
     * Create a copy with different operation ID.
     */
    public BridgeException withOperationId(String operationId) {
        return new BridgeException(errorCode, severity, getMessage(), operationId,
                                 contextData, nativeErrorCode, nativeErrorMessage,
                                 recoverable, recoverySuggestion, getCause());
    }

    /**
     * Create a copy with different severity.
     */
    public BridgeException withSeverity(ErrorSeverity severity) {
        return new BridgeException(errorCode, severity, getMessage(), operationId,
                                 contextData, nativeErrorCode, nativeErrorMessage,
                                 recoverable, recoverySuggestion, getCause());
    }

    /**
     * Create a copy with recovery information.
     */
    public BridgeException withRecovery(boolean recoverable, String recoverySuggestion) {
        return new BridgeException(errorCode, severity, getMessage(), operationId,
                                 contextData, nativeErrorCode, nativeErrorMessage,
                                 recoverable, recoverySuggestion, getCause());
    }

    /**
     * Serialize this exception to byte array.
     */
    public byte[] toByteArray() throws IOException {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             ObjectOutputStream oos = new ObjectOutputStream(baos)) {
            oos.writeObject(this);
            return baos.toByteArray();
        }
    }

    /**
     * Deserialize exception from byte array.
     */
    public static BridgeException fromByteArray(byte[] data) throws IOException, ClassNotFoundException {
        try (ByteArrayInputStream bais = new ByteArrayInputStream(data);
             ObjectInputStream ois = new ObjectInputStream(bais)) {
            return (BridgeException) ois.readObject();
        }
    }

    /**
     * Get a formatted error message including all context.
     */
    public String getDetailedMessage() {
        StringBuilder sb = new StringBuilder();
        sb.append("BridgeException: ").append(getMessage());
        sb.append(" [").append(errorCode.getCode()).append("]");

        if (operationId != null) {
            sb.append(" (Operation: ").append(operationId).append(")");
        }

        if (nativeErrorCode != 0) {
            sb.append(" (Native: ").append(nativeErrorCode);
            if (nativeErrorMessage != null) {
                sb.append(" - ").append(nativeErrorMessage);
            }
            sb.append(")");
        }

        if (!contextData.isEmpty()) {
            sb.append(" Context: ").append(contextData);
        }

        if (recoverySuggestion != null) {
            sb.append(" Suggestion: ").append(recoverySuggestion);
        }

        return sb.toString();
    }

    @Override
    public String toString() {
        return getDetailedMessage();
    }

    // Custom serialization handling
    private void writeObject(ObjectOutputStream out) throws IOException {
        out.defaultWriteObject();
    }

    private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
        in.defaultReadObject();
    }
}