package com.kneaf.core.exceptions;

/**
 * Exception thrown for native library operation failures.
 * Replaces RustPerformanceException and handles native library specific errors.
 */
public class NativeLibraryException extends KneafCoreException {
    
    public enum NativeErrorType {
        LIBRARY_NOT_AVAILABLE("Native library is not available"),
        LIBRARY_LOAD_FAILED("Failed to load native library"),
        NATIVE_CALL_FAILED("Native method call failed"),
        BINARY_PROTOCOL_ERROR("Binary protocol error"),
        JNI_ERROR("JNI error"),
        MEMORY_ALLOCATION_FAILED("Native memory allocation failed");
        
        private final String description;
        
        NativeErrorType(String description) {
            this.description = description;
        }
        
        public String getDescription() {
            return description;
        }
    }
    
    private final NativeErrorType errorType;
    private final String libraryName;
    private final String nativeMethod;
    
    public NativeLibraryException(NativeErrorType errorType, String message) {
        super(ErrorCategory.NATIVE_LIBRARY, message);
        this.errorType = errorType;
        this.libraryName = null;
        this.nativeMethod = null;
    }
    
    public NativeLibraryException(NativeErrorType errorType, String message, Throwable cause) {
        super(ErrorCategory.NATIVE_LIBRARY, message, cause);
        this.errorType = errorType;
        this.libraryName = null;
        this.nativeMethod = null;
    }
    
    public NativeLibraryException(NativeErrorType errorType, String libraryName, String nativeMethod, String message, Throwable cause) {
        super(ErrorCategory.NATIVE_LIBRARY, 
              String.format("%s [%s]", nativeMethod != null ? nativeMethod : "Native operation"), 
              message, 
              String.format("Library: %s", libraryName), 
              cause);
        this.errorType = errorType;
        this.libraryName = libraryName;
        this.nativeMethod = nativeMethod;
    }
    
    public NativeErrorType getErrorType() {
        return errorType;
    }
    
    public String getLibraryName() {
        return libraryName;
    }
    
    public String getNativeMethod() {
        return nativeMethod;
    }
    
    /**
     * Creates a native library exception when library is not available.
     */
    public static NativeLibraryException libraryNotAvailable(String libraryName) {
        return new NativeLibraryException(NativeErrorType.LIBRARY_NOT_AVAILABLE, libraryName, null, 
                                         String.format("Native library '%s' is not available", libraryName), null);
    }
    
    /**
     * Creates a native library exception for binary protocol errors.
     */
    public static NativeLibraryException binaryProtocolError(String operation, String details, Throwable cause) {
        return new NativeLibraryException(NativeErrorType.BINARY_PROTOCOL_ERROR, "rustperf", operation, 
                                         String.format("Binary protocol error in %s: %s", operation, details), cause);
    }
}