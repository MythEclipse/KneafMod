package com.kneaf.core.exceptions.nativelib;

import com.kneaf.core.exceptions.core.BaseKneafException;
import com.kneaf.core.exceptions.utils.ExceptionSeverity;
import com.kneaf.core.exceptions.utils.ExceptionContext;
import com.kneaf.core.exceptions.utils.ExceptionConstants;

/**
 * Exception thrown for native library operation failures.
 * Replaces RustPerformanceException and handles native library specific errors.
 */
public class NativeLibraryException extends BaseKneafException {
    
    public enum NativeErrorType {
        LIBRARY_NOT_AVAILABLE(ExceptionConstants.CODE_NATIVE_LIBRARY_NOT_AVAILABLE, "Native library is not available"),
        LIBRARY_LOAD_FAILED(ExceptionConstants.CODE_NATIVE_LIBRARY_LOAD_FAILED, "Failed to load native library"),
        NATIVE_CALL_FAILED(ExceptionConstants.CODE_NATIVE_CALL_FAILED, "Native method call failed"),
        BINARY_PROTOCOL_ERROR(ExceptionConstants.CODE_BINARY_PROTOCOL_ERROR, "Binary protocol error"),
        JNI_ERROR("NAT004", "JNI error"),
        MEMORY_ALLOCATION_FAILED("NAT005", "Native memory allocation failed");
        
        private final String errorCode;
        private final String description;
        
        NativeErrorType(String errorCode, String description) {
            this.errorCode = errorCode;
            this.description = description;
        }
        
        public String getErrorCode() {
            return errorCode;
        }
        
        public String getDescription() {
            return description;
        }
    }
    
    private final NativeErrorType errorType;
    private final String libraryName;
    private final String nativeMethod;
    
    private NativeLibraryException(Builder builder) {
        super(builder);
        this.errorType = builder.errorType;
        this.libraryName = builder.libraryName;
        this.nativeMethod = builder.nativeMethod;
    }
    
    /**
     * Gets the native error type
     */
    public NativeErrorType getErrorType() {
        return errorType;
    }
    
    /**
     * Gets the library name
     */
    public String getLibraryName() {
        return libraryName;
    }
    
    /**
     * Gets the native method name
     */
    public String getNativeMethod() {
        return nativeMethod;
    }
    
    @Override
    public NativeLibraryException withContext(ExceptionContext context) {
        return new Builder()
            .message(getMessage())
            .cause(getCause())
            .errorCode(getErrorCode())
            .severity(getSeverity())
            .context(context)
            .suggestion(getSuggestion())
            .logged(isLogged())
            .errorType(errorType)
            .libraryName(libraryName)
            .nativeMethod(nativeMethod)
            .build();
    }
    
    @Override
    public NativeLibraryException withSuggestion(String suggestion) {
        return new Builder()
            .message(getMessage())
            .cause(getCause())
            .errorCode(getErrorCode())
            .severity(getSeverity())
            .context(getContext())
            .suggestion(suggestion)
            .logged(isLogged())
            .errorType(errorType)
            .libraryName(libraryName)
            .nativeMethod(nativeMethod)
            .build();
    }
    
    /**
     * Creates a builder for NativeLibraryException
     */
    public static Builder builder() {
        return new Builder();
    }
    
    /**
     * Creates a native library exception when library is not available.
     */
    public static NativeLibraryException libraryNotAvailable(String libraryName) {
        return builder()
            .errorType(NativeErrorType.LIBRARY_NOT_AVAILABLE)
            .libraryName(libraryName)
            .message(String.format("Native library '%s' is not available", libraryName))
            .severity(ExceptionSeverity.ERROR)
            .suggestion(ExceptionConstants.SUGGESTION_CHECK_DEPENDENCIES)
            .build();
    }
    
    /**
     * Creates a native library exception for binary protocol errors.
     */
    public static NativeLibraryException binaryProtocolError(String operation, String details, Throwable cause) {
        return builder()
            .errorType(NativeErrorType.BINARY_PROTOCOL_ERROR)
            .libraryName("rustperf")
            .nativeMethod(operation)
            .message(String.format("Binary protocol error in %s: %s", operation, details))
            .cause(cause)
            .severity(ExceptionSeverity.ERROR)
            .suggestion(ExceptionConstants.SUGGESTION_CHECK_LOGS)
            .build();
    }
    
    /**
     * Creates a native library exception for JNI errors.
     */
    public static NativeLibraryException jniError(String libraryName, String method, String message, Throwable cause) {
        return builder()
            .errorType(NativeErrorType.JNI_ERROR)
            .libraryName(libraryName)
            .nativeMethod(method)
            .message(message)
            .cause(cause)
            .severity(ExceptionSeverity.ERROR)
            .suggestion(ExceptionConstants.SUGGESTION_CONTACT_SUPPORT)
            .build();
    }
    
    /**
     * Creates a native library exception for memory allocation failures.
     */
    public static NativeLibraryException memoryAllocationFailed(String libraryName, String operation, String details) {
        return builder()
            .errorType(NativeErrorType.MEMORY_ALLOCATION_FAILED)
            .libraryName(libraryName)
            .nativeMethod(operation)
            .message(String.format("Native memory allocation failed in %s: %s", operation, details))
            .severity(ExceptionSeverity.CRITICAL)
            .suggestion(ExceptionConstants.SUGGESTION_CHECK_RESOURCES)
            .build();
    }
    
    /**
     * Builder class for NativeLibraryException
     */
    public static class Builder extends BaseKneafException.Builder<Builder> {
        private NativeErrorType errorType;
        private String libraryName;
        private String nativeMethod;
        
        @Override
        protected Builder self() {
            return this;
        }
        
        public Builder errorType(NativeErrorType errorType) {
            this.errorType = errorType;
            return this;
        }
        
        public Builder libraryName(String libraryName) {
            this.libraryName = libraryName;
            return this;
        }
        
        public Builder nativeMethod(String nativeMethod) {
            this.nativeMethod = nativeMethod;
            return this;
        }
        
        @Override
        protected void validate() {
            super.validate();
            if (errorType == null) {
                throw new IllegalArgumentException("Error type cannot be null");
            }
        }
        
        public NativeLibraryException build() {
            validate();
            
            // Auto-generate error code if not provided
            if (errorCode == null && errorType != null) {
                errorCode = errorType.getErrorCode();
            }
            
            // Auto-set severity if not provided
            if (severity == null) {
                severity = ExceptionSeverity.ERROR;
            }
            
            return new NativeLibraryException(this);
        }
    }
}