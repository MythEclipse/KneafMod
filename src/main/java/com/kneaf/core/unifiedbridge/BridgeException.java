package com.kneaf.core.unifiedbridge;

/**
 * Base exception class for all bridge-related errors.
 */
public class BridgeException extends RuntimeException {
    private final BridgeErrorType errorType;

    /**
     * Create a new BridgeException.
     * @param message Error message
     * @param errorType Type of error
     */
    public BridgeException(String message, BridgeErrorType errorType) {
        super(message);
        this.errorType = errorType;
    }

    /**
     * Create a new BridgeException with cause.
     * @param message Error message
     * @param errorType Type of error
     * @param cause Root cause of the error
     */
    public BridgeException(String message, BridgeErrorType errorType, Throwable cause) {
        super(message, cause);
        this.errorType = errorType;
    }

    /**
     * Create a new BridgeException with message only.
     * @param message Error message
     */
    public BridgeException(String message) {
        super(message);
        this.errorType = BridgeErrorType.GENERIC_ERROR;
    }

    /**
     * Create a new BridgeException with message and cause.
     * @param message Error message
     * @param cause Root cause of the error
     */
    public BridgeException(String message, Throwable cause) {
        super(message, cause);
        this.errorType = BridgeErrorType.GENERIC_ERROR;
    }

    /**
     * Get the error type.
     * @return Error type
     */
    public BridgeErrorType getErrorType() {
        return errorType;
    }

    /**
    * Enum representing different types of bridge errors.
    */
   public enum BridgeErrorType {
       /** Generic bridge error */
       GENERIC_ERROR,
       
       /** Worker creation failed */
       WORKER_CREATION_FAILED,
       
       /** Worker destruction failed */
       WORKER_DESTROY_FAILED,
       
       /** Task processing failed */
       TASK_PROCESSING_FAILED,
       
       /** Result polling failed */
       RESULT_POLLING_FAILED,
       
       /** Buffer allocation failed */
       BUFFER_ALLOCATION_FAILED,
       
       /** Buffer access failed */
       BUFFER_ACCESS_FAILED,
       
       /** Batch processing failed */
       BATCH_PROCESSING_FAILED,
       
       /** Configuration error */
       CONFIGURATION_ERROR,
       
       /** Native call failed */
       NATIVE_CALL_FAILED,
       
       /** Native method not found */
       NATIVE_METHOD_NOT_FOUND,
       
       /** JNI call failed */
       JNI_CALL_FAILED,
       
       /** Resource management error */
       RESOURCE_MANAGEMENT_ERROR,
       
       /** Plugin error */
       PLUGIN_ERROR,
       
       /** Shutdown error */
       SHUTDOWN_ERROR,
       
       /** Invalid state error */
       INVALID_STATE_ERROR,
       
       /** Compatibility error */
       COMPATIBILITY_ERROR,
       
       /** Metrics collection error */
       METRICS_ERROR
   }
}