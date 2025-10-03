package com.kneaf.core.protocol.utils;

import com.kneaf.core.protocol.core.ProtocolConstants;
import com.kneaf.core.protocol.core.ProtocolLogger;
import com.kneaf.core.protocol.core.ProtocolValidator;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Context for protocol execution containing configuration, state, and metadata.
 */
public class ProtocolContext {
    
    private final String traceId;
    private final String operation;
    private final String protocolFormat;
    private final String protocolVersion;
    private final Instant startTime;
    private final Map<String, Object> metadata;
    private final ProtocolLogger logger;
    private final ProtocolValidator<?> validator;
    
    private ProtocolContext(Builder builder) {
        this.traceId = builder.traceId;
        this.operation = builder.operation;
        this.protocolFormat = builder.protocolFormat;
        this.protocolVersion = builder.protocolVersion;
        this.startTime = builder.startTime;
        this.metadata = new HashMap<>(builder.metadata);
        this.logger = builder.logger;
        this.validator = builder.validator;
    }
    
    // Getters
    public String getTraceId() {
        return traceId;
    }
    
    public String getOperation() {
        return operation;
    }
    
    public String getProtocolFormat() {
        return protocolFormat;
    }
    
    public String getProtocolVersion() {
        return protocolVersion;
    }
    
    public Instant getStartTime() {
        return startTime;
    }
    
    public Map<String, Object> getMetadata() {
        return new HashMap<>(metadata);
    }
    
    public ProtocolLogger getLogger() {
        return logger;
    }
    
    public ProtocolValidator<?> getValidator() {
        return validator;
    }
    
    /**
     * Add metadata to the context.
     * 
     * @param key the metadata key
     * @param value the metadata value
     */
    public void addMetadata(String key, Object value) {
        metadata.put(key, value);
    }
    
    /**
     * Get metadata value.
     * 
     * @param key the metadata key
     * @return the metadata value or null if not found
     */
    public Object getMetadata(String key) {
        return metadata.get(key);
    }
    
    /**
     * Calculate elapsed time since context creation.
     * 
     * @return elapsed time in milliseconds
     */
    public long getElapsedTime() {
        return java.time.Duration.between(startTime, Instant.now()).toMillis();
    }
    
    /**
     * Create a copy of this context with new metadata.
     * 
     * @param additionalMetadata additional metadata to add
     * @return new context instance
     */
    public ProtocolContext withMetadata(Map<String, Object> additionalMetadata) {
        Map<String, Object> newMetadata = new HashMap<>(metadata);
        newMetadata.putAll(additionalMetadata);
        
        return new Builder()
            .traceId(traceId)
            .operation(operation)
            .protocolFormat(protocolFormat)
            .protocolVersion(protocolVersion)
            .startTime(startTime)
            .metadata(newMetadata)
            .logger(logger)
            .validator(validator)
            .build();
    }
    
    /**
     * Create a builder for ProtocolContext.
     * 
     * @return new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }
    
    /**
     * Builder for ProtocolContext.
     */
    public static class Builder {
        private String traceId;
        private String operation;
        private String protocolFormat = ProtocolConstants.FORMAT_JSON;
        private String protocolVersion = ProtocolConstants.CURRENT_VERSION;
        private Instant startTime = Instant.now();
        private Map<String, Object> metadata = new HashMap<>();
        private ProtocolLogger logger;
        private ProtocolValidator<?> validator;
        
        public Builder traceId(String traceId) {
            this.traceId = traceId;
            return this;
        }
        
        public Builder operation(String operation) {
            this.operation = operation;
            return this;
        }
        
        public Builder protocolFormat(String protocolFormat) {
            this.protocolFormat = protocolFormat;
            return this;
        }
        
        public Builder protocolVersion(String protocolVersion) {
            this.protocolVersion = protocolVersion;
            return this;
        }
        
        public Builder startTime(Instant startTime) {
            this.startTime = startTime;
            return this;
        }
        
        public Builder metadata(Map<String, Object> metadata) {
            this.metadata = new HashMap<>(metadata);
            return this;
        }
        
        public Builder addMetadata(String key, Object value) {
            this.metadata.put(key, value);
            return this;
        }
        
        public Builder logger(ProtocolLogger logger) {
            this.logger = logger;
            return this;
        }
        
        public Builder validator(ProtocolValidator<?> validator) {
            this.validator = validator;
            return this;
        }
        
        /**
         * Build the ProtocolContext.
         * 
         * @return new ProtocolContext instance
         * @throws IllegalStateException if required fields are missing
         */
        public ProtocolContext build() {
            if (traceId == null) {
                traceId = UUID.randomUUID().toString();
            }
            
            if (operation == null) {
                throw new IllegalStateException("Operation is required");
            }
            
            if (logger == null) {
                logger = new ProtocolLoggerImpl("ProtocolContext");
            }
            
            if (validator == null) {
                validator = new ProtocolValidatorImpl<>(Object.class, ProtocolConstants.MAX_JSON_SIZE, protocolVersion);
            }
            
            return new ProtocolContext(this);
        }
    }
    
    /**
     * Create a default context for the given operation.
     * 
     * @param operation the operation name
     * @return default context
     */
    public static ProtocolContext createDefault(String operation) {
        return builder()
            .operation(operation)
            .build();
    }
    
    /**
     * Create a context for JSON protocol operations.
     * 
     * @param operation the operation name
     * @return JSON protocol context
     */
    public static ProtocolContext forJson(String operation) {
        return builder()
            .operation(operation)
            .protocolFormat(ProtocolConstants.FORMAT_JSON)
            .build();
    }
    
    /**
     * Create a context for binary protocol operations.
     * 
     * @param operation the operation name
     * @return binary protocol context
     */
    public static ProtocolContext forBinary(String operation) {
        return builder()
            .operation(operation)
            .protocolFormat(ProtocolConstants.FORMAT_BINARY)
            .build();
    }
}