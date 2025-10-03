package com.kneaf.core.protocol;

import com.kneaf.core.exceptions.NativeLibraryException;
import com.kneaf.core.exceptions.KneafCoreException;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Protocol abstraction layer that provides unified binary/JSON dual protocol processing.
 * Eliminates the duplicate patterns of "try binary, fallback to JSON" found throughout the codebase.
 */
public class ProtocolProcessor {
    
    private static final Logger LOGGER = LoggerFactory.getLogger(ProtocolProcessor.class);
    private static final Gson GSON = new Gson();
    
    /**
     * Protocol types supported by the processor.
     */
    public enum ProtocolType {
        BINARY("Binary"),
        JSON("JSON"),
        AUTO("Auto"); // Try binary first, fallback to JSON
        
        private final String name;
        
        ProtocolType(String name) {
            this.name = name;
        }
        
        public String getName() {
            return name;
        }
    }
    
    /**
     * Result wrapper for protocol operations.
     */
    public static class ProtocolResult<T> {
        private final T data;
        private final ProtocolType protocolUsed;
        private final boolean success;
        private final String errorMessage;
        
        private ProtocolResult(T data, ProtocolType protocolUsed, boolean success, String errorMessage) {
            this.data = data;
            this.protocolUsed = protocolUsed;
            this.success = success;
            this.errorMessage = errorMessage;
        }
        
        public static <T> ProtocolResult<T> success(T data, ProtocolType protocolUsed) {
            return new ProtocolResult<>(data, protocolUsed, true, null);
        }
        
        public static <T> ProtocolResult<T> failure(String errorMessage) {
            return new ProtocolResult<>(null, null, false, errorMessage);
        }
        
        public T getData() { return data; }
        public ProtocolType getProtocolUsed() { return protocolUsed; }
        public boolean isSuccess() { return success; }
        public String getErrorMessage() { return errorMessage; }
        
        public T getDataOrThrow() {
            if (!success) {
                throw new KneafCoreException(KneafCoreException.ErrorCategory.PROTOCOL_ERROR, 
                                           "Protocol processing failed: " + errorMessage);
            }
            return data;
        }
    }
    
    /**
     * Functional interfaces for protocol operations.
     */
    @FunctionalInterface
    public interface BinarySerializer<T> {
        ByteBuffer serialize(T input) throws Exception;
    }
    
    @FunctionalInterface
    public interface BinaryNativeCaller<R> {
        R callNative(ByteBuffer input) throws Exception;
    }
    
    @FunctionalInterface
    public interface BinaryDeserializer<R> {
        R deserialize(byte[] resultBytes) throws Exception;
    }
    
    @FunctionalInterface
    public interface JsonInputPreparer<T> {
        Map<String, Object> prepareInput(T input);
    }
    
    @FunctionalInterface
    public interface JsonNativeCaller<R> {
        R callNative(String jsonInput) throws Exception;
    }
    
    @FunctionalInterface
    public interface JsonResultParser<R> {
        R parseResult(String jsonResult) throws Exception;
    }
    
    private final boolean nativeAvailable;
    private final ProtocolType defaultProtocol;
    
    public ProtocolProcessor(boolean nativeAvailable, ProtocolType defaultProtocol) {
        this.nativeAvailable = nativeAvailable;
        this.defaultProtocol = defaultProtocol;
    }
    
    /**
     * Process data using the specified protocol with automatic fallback.
     * This is the main method that eliminates the duplicate "try binary, fallback to JSON" patterns.
     */
    public <T, R> ProtocolResult<R> processWithFallback(
            T input,
            String operationName,
            BinarySerializer<T> binarySerializer,
            BinaryNativeCaller<byte[]> binaryNativeCaller,
            BinaryDeserializer<R> binaryDeserializer,
            JsonInputPreparer<T> jsonInputPreparer,
            JsonNativeCaller<String> jsonNativeCaller,
            JsonResultParser<R> jsonResultParser,
            R fallbackResult) {
        
        if (defaultProtocol == ProtocolType.JSON || !nativeAvailable) {
            return processJson(input, operationName, jsonInputPreparer, jsonNativeCaller, jsonResultParser, fallbackResult);
        }
        
        if (defaultProtocol == ProtocolType.BINARY) {
            return processBinary(input, operationName, binarySerializer, binaryNativeCaller, binaryDeserializer, fallbackResult);
        }
        
        // AUTO: Try binary first, fallback to JSON
        ProtocolResult<R> binaryResult = processBinary(input, operationName, binarySerializer, binaryNativeCaller, binaryDeserializer, null);
        if (binaryResult.isSuccess()) {
            return binaryResult;
        }
        
        LOGGER.debug("Binary protocol failed for {}, falling back to JSON: {}", operationName, binaryResult.getErrorMessage());
        return processJson(input, operationName, jsonInputPreparer, jsonNativeCaller, jsonResultParser, fallbackResult);
    }
    
    /**
     * Process using binary protocol only.
     */
    private <T, R> ProtocolResult<R> processBinary(
            T input,
            String operationName,
            BinarySerializer<T> binarySerializer,
            BinaryNativeCaller<byte[]> binaryNativeCaller,
            BinaryDeserializer<R> binaryDeserializer,
            R fallbackResult) {
        
        if (!nativeAvailable) {
            String message = "Native library not available for binary protocol";
            LOGGER.debug("{}: {}", message, operationName);
            return fallbackResult != null ? 
                ProtocolResult.success(fallbackResult, ProtocolType.BINARY) : 
                ProtocolResult.failure(message);
        }
        
        try {
            // Serialize to binary format
            ByteBuffer inputBuffer = binarySerializer.serialize(input);
            
            // Call native method
            byte[] resultBytes = binaryNativeCaller.callNative(inputBuffer);
            
            if (resultBytes == null) {
                String message = "Native method returned null";
                LOGGER.debug("{} for {}: {}", message, operationName, operationName);
                return fallbackResult != null ? 
                    ProtocolResult.success(fallbackResult, ProtocolType.BINARY) : 
                    ProtocolResult.failure(message);
            }
            
            // Log binary result for debugging
            logBinaryResult(operationName, resultBytes);
            
            // Deserialize result
            R result = binaryDeserializer.deserialize(resultBytes);
            return ProtocolResult.success(result, ProtocolType.BINARY);
            
        } catch (Exception e) {
            String message = String.format("Binary protocol processing failed: %s", e.getMessage());
            LOGGER.debug("{} for {}: {}", message, operationName, e.getMessage());
            
            if (fallbackResult != null) {
                return ProtocolResult.success(fallbackResult, ProtocolType.BINARY);
            }
            return ProtocolResult.failure(message);
        }
    }
    
    /**
     * Process using JSON protocol only.
     */
    private <T, R> ProtocolResult<R> processJson(
            T input,
            String operationName,
            JsonInputPreparer<T> jsonInputPreparer,
            JsonNativeCaller<String> jsonNativeCaller,
            JsonResultParser<R> jsonResultParser,
            R fallbackResult) {
        
        try {
            // Prepare JSON input
            Map<String, Object> jsonInputMap = jsonInputPreparer.prepareInput(input);
            String jsonInput = GSON.toJson(jsonInputMap);
            
            // Call native method
            String jsonResult = jsonNativeCaller.callNative(jsonInput);
            
            if (jsonResult == null) {
                String message = "Native method returned null";
                LOGGER.warn("{} for {} returned null, using fallback", operationName, operationName);
                return fallbackResult != null ? 
                    ProtocolResult.success(fallbackResult, ProtocolType.JSON) : 
                    ProtocolResult.failure(message);
            }
            
            // Parse result
            R result = jsonResultParser.parseResult(jsonResult);
            return ProtocolResult.success(result, ProtocolType.JSON);
            
        } catch (Exception e) {
            String message = String.format("JSON protocol processing failed: %s", e.getMessage());
            LOGGER.warn("{} for {}: {}", message, operationName, e.getMessage(), e);
            
            if (fallbackResult != null) {
                return ProtocolResult.success(fallbackResult, ProtocolType.JSON);
            }
            return ProtocolResult.failure(message);
        }
    }
    
    /**
     * Log binary result for debugging purposes.
     */
    private void logBinaryResult(String operationName, byte[] resultBytes) {
        if (!LOGGER.isDebugEnabled()) {
            return;
        }
        
        try {
            String prefix = bytesPrefixHex(resultBytes, 64);
            LOGGER.debug("[BINARY] Received {} bytes from native for {}: prefix={}", 
                        resultBytes.length, operationName, prefix);
            
            // Try to parse as little-endian int/long for additional debugging
            if (resultBytes.length >= 4) {
                ByteBuffer buffer = ByteBuffer.wrap(resultBytes).order(ByteOrder.LITTLE_ENDIAN);
                int int0 = buffer.getInt(0);
                LOGGER.debug("[BINARY] First int: {}", int0);
                
                if (resultBytes.length >= 8) {
                    long long0 = buffer.getLong(0);
                    LOGGER.debug("[BINARY] First long: {}", long0);
                }
            }
        } catch (Exception e) {
            LOGGER.debug("[BINARY] Failed to log binary result details: {}", e.getMessage());
        }
    }
    
    /**
     * Helper to produce a short hex prefix of a byte array for logging.
     */
    private String bytesPrefixHex(byte[] data, int maxBytes) {
        if (data == null) return "";
        int len = Math.min(data.length, Math.max(0, maxBytes));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            sb.append(String.format("%02x", data[i] & 0xff));
            if (i < len - 1) sb.append(',');
        }
        if (data.length > len) sb.append("...");
        return sb.toString();
    }
    
    /**
     * Create a protocol processor with auto-detection.
     */
    public static ProtocolProcessor createAuto(boolean nativeAvailable) {
        return new ProtocolProcessor(nativeAvailable, ProtocolType.AUTO);
    }
    
    /**
     * Create a protocol processor that prefers binary protocol.
     */
    public static ProtocolProcessor createBinary(boolean nativeAvailable) {
        return new ProtocolProcessor(nativeAvailable, ProtocolType.BINARY);
    }
    
    /**
     * Create a protocol processor that uses JSON protocol only.
     */
    public static ProtocolProcessor createJson() {
        return new ProtocolProcessor(false, ProtocolType.JSON);
    }
}