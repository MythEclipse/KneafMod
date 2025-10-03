package com.kneaf.core.exceptions;

/**
 * Legacy compatibility wrapper for NativeLibraryException.
 * Extends the new enhanced exception class to maintain backward compatibility.
 * 
 * @deprecated Use com.kneaf.core.exceptions.nativelib.NativeLibraryException instead
 */
@Deprecated
public class NativeLibraryException extends RuntimeException {
    
    private final com.kneaf.core.exceptions.nativelib.NativeLibraryException delegate;
    private final NativeErrorType errorType;
    private final String libraryName;
    private final String nativeMethod;
    
    public NativeLibraryException(NativeErrorType errorType, String message) {
        super(message);
        this.delegate = com.kneaf.core.exceptions.nativelib.NativeLibraryException.builder()
            .errorType(convertErrorType(errorType))
            .message(message)
            .build();
        this.errorType = errorType;
        this.libraryName = null;
        this.nativeMethod = null;
    }
    
    public NativeLibraryException(NativeErrorType errorType, String message, Throwable cause) {
        super(message, cause);
        this.delegate = com.kneaf.core.exceptions.nativelib.NativeLibraryException.builder()
            .errorType(convertErrorType(errorType))
            .message(message)
            .cause(cause)
            .build();
        this.errorType = errorType;
        this.libraryName = null;
        this.nativeMethod = null;
    }
    
    public NativeLibraryException(NativeErrorType errorType, String libraryName, String nativeMethod, String message, Throwable cause) {
        super(message, cause);
        this.delegate = com.kneaf.core.exceptions.nativelib.NativeLibraryException.builder()
            .errorType(convertErrorType(errorType))
            .libraryName(libraryName)
            .nativeMethod(nativeMethod)
            .message(message)
            .cause(cause)
            .build();
        this.errorType = errorType;
        this.libraryName = libraryName;
        this.nativeMethod = nativeMethod;
    }
    
    /**
     * Gets the native error type
     */
    public NativeErrorType getErrorType() {
        return errorType != null ? errorType : convertErrorTypeBack(delegate.getErrorType());
    }
    
    /**
     * Gets the library name
     */
    public String getLibraryName() {
        return libraryName != null ? libraryName : delegate.getLibraryName();
    }
    
    /**
     * Gets the native method name
     */
    public String getNativeMethod() {
        return nativeMethod != null ? nativeMethod : delegate.getNativeMethod();
    }
    
    /**
     * Gets the delegate exception for access to new functionality
     */
    public com.kneaf.core.exceptions.nativelib.NativeLibraryException getDelegate() {
        return delegate;
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
    
    private static com.kneaf.core.exceptions.nativelib.NativeLibraryException.NativeErrorType convertErrorType(NativeErrorType errorType) {
        if (errorType == null) return null;
        
        switch (errorType) {
            case LIBRARY_NOT_AVAILABLE:
                return com.kneaf.core.exceptions.nativelib.NativeLibraryException.NativeErrorType.LIBRARY_NOT_AVAILABLE;
            case LIBRARY_LOAD_FAILED:
                return com.kneaf.core.exceptions.nativelib.NativeLibraryException.NativeErrorType.LIBRARY_LOAD_FAILED;
            case NATIVE_CALL_FAILED:
                return com.kneaf.core.exceptions.nativelib.NativeLibraryException.NativeErrorType.NATIVE_CALL_FAILED;
            case BINARY_PROTOCOL_ERROR:
                return com.kneaf.core.exceptions.nativelib.NativeLibraryException.NativeErrorType.BINARY_PROTOCOL_ERROR;
            case JNI_ERROR:
                return com.kneaf.core.exceptions.nativelib.NativeLibraryException.NativeErrorType.JNI_ERROR;
            case MEMORY_ALLOCATION_FAILED:
                return com.kneaf.core.exceptions.nativelib.NativeLibraryException.NativeErrorType.MEMORY_ALLOCATION_FAILED;
            default:
                return com.kneaf.core.exceptions.nativelib.NativeLibraryException.NativeErrorType.NATIVE_CALL_FAILED;
        }
    }
    
    private static NativeErrorType convertErrorTypeBack(com.kneaf.core.exceptions.nativelib.NativeLibraryException.NativeErrorType errorType) {
        if (errorType == null) return null;
        
        switch (errorType) {
            case LIBRARY_NOT_AVAILABLE:
                return NativeErrorType.LIBRARY_NOT_AVAILABLE;
            case LIBRARY_LOAD_FAILED:
                return NativeErrorType.LIBRARY_LOAD_FAILED;
            case NATIVE_CALL_FAILED:
                return NativeErrorType.NATIVE_CALL_FAILED;
            case BINARY_PROTOCOL_ERROR:
                return NativeErrorType.BINARY_PROTOCOL_ERROR;
            case JNI_ERROR:
                return NativeErrorType.JNI_ERROR;
            case MEMORY_ALLOCATION_FAILED:
                return NativeErrorType.MEMORY_ALLOCATION_FAILED;
            default:
                return NativeErrorType.NATIVE_CALL_FAILED;
        }
    }
    
    /**
     * Native error types for backward compatibility
     */
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
}