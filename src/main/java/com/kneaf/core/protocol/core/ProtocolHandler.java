package com.kneaf.core.protocol.core;

import java.util.concurrent.CompletableFuture;

/**
 * Base interface for protocol handlers supporting multiple protocol formats.
 */
public interface ProtocolHandler<T, R> {
    
    /**
     * Process data using binary protocol format.
     * 
     * @param input the input data
     * @return the processing result
     * @throws Exception if processing fails
     */
    R processBinary(T input) throws Exception;
    
    /**
     * Process data using JSON protocol format.
     * 
     * @param input the input data
     * @return the processing result
     * @throws Exception if processing fails
     */
    R processJson(T input) throws Exception;
    
    /**
     * Process data with automatic format detection and fallback.
     * 
     * @param input the input data
     * @return the processing result
     * @throws Exception if all formats fail
     */
    R processWithFallback(T input) throws Exception;
    
    /**
     * Process data asynchronously with automatic format detection.
     * 
     * @param input the input data
     * @return future containing the processing result
     */
    CompletableFuture<R> processAsync(T input);
    
    /**
     * Get the protocol version supported by this handler.
     * 
     * @return protocol version string
     */
    String getProtocolVersion();
    
    /**
     * Check if this handler supports the given protocol format.
     * 
     * @param format the protocol format (binary, json, etc.)
     * @return true if supported
     */
    boolean supportsFormat(String format);
}