package com.kneaf.core.protocol.utils;

import com.kneaf.core.protocol.core.ProtocolConstants;
import com.kneaf.core.protocol.core.ProtocolUtils;
import com.kneaf.core.protocol.core.ProtocolValidator;
import com.kneaf.core.protocol.core.ProtocolException;

import java.util.ArrayList;
import java.util.List;

/**
 * Default implementation of ProtocolValidator with comprehensive validation rules.
 */
public class ProtocolValidatorImpl<T> implements ProtocolValidator<T> {
    
    private final Class<T> dataType;
    private final int maxSize;
    private final String supportedVersion;
    
    /**
     * Create a new protocol validator.
     * 
     * @param dataType the expected data type
     * @param maxSize maximum allowed size in bytes
     * @param supportedVersion the supported protocol version
     */
    public ProtocolValidatorImpl(Class<T> dataType, int maxSize, String supportedVersion) {
        this.dataType = dataType;
        this.maxSize = maxSize;
        this.supportedVersion = supportedVersion;
    }
    
    @Override
    public ValidationResult validate(T data) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        
        // Validate data is not null
        if (data == null) {
            errors.add("Data cannot be null");
            return ValidationResult.invalid(errors);
        }
        
        // Validate data type
        if (!dataType.isInstance(data)) {
            errors.add("Invalid data type. Expected: " + dataType.getSimpleName() + 
                      ", Actual: " + data.getClass().getSimpleName());
        }
        
        // Validate size
        if (ProtocolUtils.exceedsSizeLimit(data, maxSize)) {
            errors.add("Data size exceeds maximum allowed size of " + maxSize + " bytes");
        }
        
        // Validate size against format-specific limits
        int dataSize = ProtocolUtils.getSizeInBytes(data);
        if (data instanceof String) {
            String str = (String) data;
            if (ProtocolUtils.isValidJson(str) && dataSize > ProtocolConstants.MAX_JSON_SIZE) {
                errors.add("JSON data size exceeds limit of " + ProtocolConstants.MAX_JSON_SIZE + " bytes");
            } else if (dataSize > ProtocolConstants.MAX_BINARY_SIZE) {
                errors.add("Binary data size exceeds limit of " + ProtocolConstants.MAX_BINARY_SIZE + " bytes");
            }
        } else if (data instanceof byte[] || data instanceof java.nio.ByteBuffer) {
            if (dataSize > ProtocolConstants.MAX_BINARY_SIZE) {
                errors.add("Binary data size exceeds limit of " + ProtocolConstants.MAX_BINARY_SIZE + " bytes");
            }
        }
        
        // Add warnings for large data
        if (dataSize > maxSize * 0.8) {
            warnings.add("Data size is approaching maximum limit (" + 
                        String.format("%.1f%%", (dataSize * 100.0 / maxSize)) + ")");
        }
        
        return errors.isEmpty() ? ValidationResult.valid() : ValidationResult.invalid(errors, warnings);
    }
    
    @Override
    public boolean validateFormat(T data, String format) {
        if (data == null || format == null) {
            return false;
        }
        
        switch (format.toLowerCase()) {
            case ProtocolConstants.FORMAT_JSON:
                return data instanceof String && ProtocolUtils.isValidJson((String) data);
            case ProtocolConstants.FORMAT_BINARY:
                return data instanceof byte[] || data instanceof java.nio.ByteBuffer;
            case ProtocolConstants.FORMAT_XML:
                return data instanceof String && isValidXml((String) data);
            default:
                return false;
        }
    }
    
    @Override
    public boolean validateVersion(T data, String protocolVersion) {
        if (protocolVersion == null) {
            return false;
        }
        
        // Simple version comparison - can be enhanced with semantic versioning
        return supportedVersion.equals(protocolVersion);
    }
    
    @Override
    public boolean validateSize(T data) {
        if (data == null) {
            return false;
        }
        
        int size = ProtocolUtils.getSizeInBytes(data);
        return size <= maxSize;
    }
    
    @Override
    public boolean validateIntegrity(T data) {
        if (data == null) {
            return false;
        }
        
        try {
            // For strings, validate that they don't contain invalid characters
            if (data instanceof String) {
                String str = (String) data;
                // Check for null characters and other control characters that might cause issues
                return !str.contains("\0") && !containsInvalidControlChars(str);
            }
            
            // For binary data, basic validation that it's not empty
            if (data instanceof byte[]) {
                return ((byte[]) data).length > 0;
            }
            
            if (data instanceof java.nio.ByteBuffer) {
                return ((java.nio.ByteBuffer) data).remaining() > 0;
            }
            
            // For other types, basic validation passes
            return true;
            
        } catch (Exception e) {
            return false;
        }
    }
    
    @Override
    public List<String> getSupportedRules() {
        return List.of(
            ProtocolConstants.VALIDATION_FORMAT,
            ProtocolConstants.VALIDATION_VERSION,
            ProtocolConstants.VALIDATION_SIZE,
            ProtocolConstants.VALIDATION_INTEGRITY,
            ProtocolConstants.VALIDATION_SCHEMA
        );
    }
    
    /**
     * Check if string contains invalid control characters.
     * 
     * @param str the string to check
     * @return true if contains invalid characters
     */
    private boolean containsInvalidControlChars(String str) {
        for (char c : str.toCharArray()) {
            if (Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t') {
                return true;
            }
        }
        return false;
    }
    
    /**
     * Basic XML validation - checks for well-formed structure.
     * 
     * @param xml the XML string
     * @return true if valid XML structure
     */
    private boolean isValidXml(String xml) {
        if (xml == null || xml.trim().isEmpty()) {
            return false;
        }
        
        String trimmed = xml.trim();
        
        // Basic XML structure checks
        if (!trimmed.startsWith("<") || !trimmed.endsWith(">")) {
            return false;
        }
        
        // Check for matching opening and closing tags
        int openTags = countOccurrences(trimmed, "<");
        int closeTags = countOccurrences(trimmed, ">");
        
        return openTags == closeTags && openTags > 0;
    }
    
    /**
     * Count occurrences of substring in string.
     * 
     * @param str the string to search
     * @param sub the substring to count
     * @return number of occurrences
     */
    private int countOccurrences(String str, String sub) {
        int count = 0;
        int index = 0;
        while ((index = str.indexOf(sub, index)) != -1) {
            count++;
            index += sub.length();
        }
        return count;
    }
    
    /**
     * Create a validator for JSON data.
     * 
     * @return JSON validator
     */
    public static ProtocolValidatorImpl<String> createJsonValidator() {
        return new ProtocolValidatorImpl<>(String.class, ProtocolConstants.MAX_JSON_SIZE, ProtocolConstants.CURRENT_VERSION);
    }
    
    /**
     * Create a validator for binary data.
     * 
     * @return binary validator
     */
    public static ProtocolValidatorImpl<byte[]> createBinaryValidator() {
        return new ProtocolValidatorImpl<>(byte[].class, ProtocolConstants.MAX_BINARY_SIZE, ProtocolConstants.CURRENT_VERSION);
    }
}