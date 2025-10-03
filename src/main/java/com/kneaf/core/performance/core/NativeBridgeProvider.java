package com.kneaf.core.performance.core;

import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;

/**
 * Interface for native bridge operations that communicate with Rust code.
 */
public interface NativeBridgeProvider {
    
    /**
     * Initialize the native library and allocator.
     */
    boolean initialize();
    
    /**
     * Check if native library is available and loaded.
     */
    boolean isNativeAvailable();
    
    /**
     * Process entities using native binary protocol.
     */
    byte[] processEntitiesBinary(ByteBuffer input);
    
    /**
     * Process entities using JSON protocol.
     */
    String processEntitiesJson(String jsonInput);
    
    /**
     * Process item entities using native binary protocol.
     */
    byte[] processItemEntitiesBinary(ByteBuffer input);
    
    /**
     * Process item entities using JSON protocol.
     */
    String processItemEntitiesJson(String jsonInput);
    
    /**
     * Process mob AI using native binary protocol.
     */
    byte[] processMobAiBinary(ByteBuffer input);
    
    /**
     * Process mob AI using JSON protocol.
     */
    String processMobAiJson(String jsonInput);
    
    /**
     * Process villager AI using native binary protocol.
     */
    byte[] processVillagerAiBinary(ByteBuffer input);
    
    /**
     * Process villager AI using JSON protocol.
     */
    String processVillagerAiJson(String jsonInput);
    
    /**
     * Process block entities using native binary protocol.
     */
    byte[] processBlockEntitiesBinary(ByteBuffer input);
    
    /**
     * Process block entities using JSON protocol.
     */
    String processBlockEntitiesJson(String jsonInput);
    
    /**
     * Get memory statistics from native code.
     */
    String getMemoryStats();
    
    /**
     * Get CPU statistics from native code.
     */
    String getCpuStats();
    
    /**
     * Pre-generate nearby chunks asynchronously.
     */
    CompletableFuture<Integer> preGenerateNearbyChunksAsync(int centerX, int centerZ, int radius);
    
    /**
     * Check if chunk is generated.
     */
    boolean isChunkGenerated(int x, int z);
    
    /**
     * Get generated chunk count.
     */
    long getGeneratedChunkCount();
    
    /**
     * Get native worker queue depth.
     */
    int getNativeWorkerQueueDepth();
    
    /**
     * Get native worker average processing time.
     */
    double getNativeWorkerAvgProcessingMs();
}