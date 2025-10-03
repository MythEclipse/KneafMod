package com.kneaf.core.protocol.core;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

/**
 * Utility methods for protocol operations including parsing, validation, and conversion.
 */
public final class ProtocolUtils {
    
    private static final Gson GSON = new Gson();
    
    // Prevent instantiation
    private ProtocolUtils() {
        throw new UnsupportedOperationException("Utility class");
    }
    
    /**
     * Generate a unique trace ID for protocol operations.
     * 
     * @return unique trace identifier
     */
    public static String generateTraceId() {
        return UUID.randomUUID().toString();
    }
    
    /**
     * Parse JSON string to Map.
     * 
     * @param json the JSON string
     * @return parsed map
     * @throws JsonSyntaxException if JSON is invalid
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseJson(String json) throws JsonSyntaxException {
        return GSON.fromJson(json, Map.class);
    }
    
    /**
     * Convert object to JSON string.
     * 
     * @param obj the object to convert
     * @return JSON string
     */
    public static String toJson(Object obj) {
        return GSON.toJson(obj);
    }
    
    /**
     * Validate JSON string format.
     * 
     * @param json the JSON string to validate
     * @return true if valid JSON
     */
    public static boolean isValidJson(String json) {
        try {
            JsonParser.parseString(json);
            return true;
        } catch (JsonSyntaxException e) {
            return false;
        }
    }
    
    /**
     * Calculate SHA-256 hash of data.
     * 
     * @param data the data to hash
     * @return base64 encoded hash
     * @throws NoSuchAlgorithmException if SHA-256 is not available
     */
    public static String calculateHash(byte[] data) throws NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(data);
        return Base64.getEncoder().encodeToString(hash);
    }
    
    /**
     * Calculate SHA-256 hash of string.
     * 
     * @param data the string to hash
     * @return base64 encoded hash
     * @throws NoSuchAlgorithmException if SHA-256 is not available
     */
    public static String calculateHash(String data) throws NoSuchAlgorithmException {
        return calculateHash(data.getBytes(StandardCharsets.UTF_8));
    }
    
    /**
     * Convert ByteBuffer to byte array.
     * 
     * @param buffer the ByteBuffer
     * @return byte array
     */
    public static byte[] toByteArray(ByteBuffer buffer) {
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return bytes;
    }
    
    /**
     * Convert byte array to ByteBuffer.
     * 
     * @param bytes the byte array
     * @return ByteBuffer
     */
    public static ByteBuffer toByteBuffer(byte[] bytes) {
        return ByteBuffer.wrap(bytes);
    }
    
    /**
     * Get size of data in bytes.
     * 
     * @param data the data
     * @return size in bytes
     */
    public static int getSizeInBytes(Object data) {
        if (data instanceof String) {
            return ((String) data).getBytes(StandardCharsets.UTF_8).length;
        } else if (data instanceof byte[]) {
            return ((byte[]) data).length;
        } else if (data instanceof ByteBuffer) {
            return ((ByteBuffer) data).remaining();
        } else {
            return toJson(data).getBytes(StandardCharsets.UTF_8).length;
        }
    }
    
    /**
     * Check if data size exceeds limit.
     * 
     * @param data the data to check
     * @param maxSize maximum allowed size in bytes
     * @return true if size exceeds limit
     */
    public static boolean exceedsSizeLimit(Object data, int maxSize) {
        return getSizeInBytes(data) > maxSize;
    }
    
    /**
     * Sanitize string for protocol transmission.
     * 
     * @param input the input string
     * @return sanitized string
     */
    public static String sanitizeString(String input) {
        if (input == null) {
            return null;
        }
        // Remove control characters and trim
        return input.replaceAll("[\\p{Cntrl}&&[^\r\n\t]]", "").trim();
    }
    
    /**
     * Truncate string to maximum length.
     * 
     * @param input the input string
     * @param maxLength maximum length
     * @return truncated string
     */
    public static String truncateString(String input, int maxLength) {
        if (input == null || input.length() <= maxLength) {
            return input;
        }
        return input.substring(0, maxLength);
    }
    
    /**
     * Create error response with standard format.
     * 
     * @param errorCode the error code
     * @param errorMessage the error message
     * @param traceId the trace ID
     * @return error response map
     */
    public static Map<String, Object> createErrorResponse(int errorCode, String errorMessage, String traceId) {
        return Map.of(
            "success", false,
            "errorCode", errorCode,
            "errorMessage", errorMessage,
            "traceId", traceId,
            "timestamp", System.currentTimeMillis()
        );
    }
    
    /**
     * Create success response with standard format.
     * 
     * @param data the response data
     * @param traceId the trace ID
     * @return success response map
     */
    public static Map<String, Object> createSuccessResponse(Object data, String traceId) {
        return Map.of(
            "success", true,
            "data", data,
            "traceId", traceId,
            "timestamp", System.currentTimeMillis()
        );
    }
    
    /**
     * Determine protocol format from content type or data.
     * 
     * @param contentType the content type
     * @param data the data
     * @return detected format
     */
    public static String detectFormat(String contentType, Object data) {
        if (contentType != null) {
            if (contentType.contains("json")) {
                return ProtocolConstants.FORMAT_JSON;
            } else if (contentType.contains("xml")) {
                return ProtocolConstants.FORMAT_XML;
            } else if (contentType.contains("protobuf")) {
                return ProtocolConstants.FORMAT_PROTOBUF;
            } else if (contentType.contains("octet-stream")) {
                return ProtocolConstants.FORMAT_BINARY;
            }
        }
        
        if (data instanceof String) {
            String str = (String) data;
            if (isValidJson(str)) {
                return ProtocolConstants.FORMAT_JSON;
            }
        } else if (data instanceof byte[] || data instanceof ByteBuffer) {
            return ProtocolConstants.FORMAT_BINARY;
        }
        
        return ProtocolConstants.FORMAT_JSON; // Default fallback
    }
}