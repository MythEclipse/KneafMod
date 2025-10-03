package com.kneaf.core.performance;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.HashSet;
import java.io.*;
import java.nio.file.*;

import com.kneaf.core.KneafCore;
import com.kneaf.core.data.EntityData;
import com.kneaf.core.data.ItemEntityData;
import com.kneaf.core.data.MobData;
import com.kneaf.core.data.BlockEntityData;
import com.kneaf.core.data.PlayerData;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.ConcurrentLinkedQueue;
import com.kneaf.core.binary.ManualSerializers;
import com.kneaf.core.async.AsyncProcessor;
import com.kneaf.core.protocol.ProtocolProcessor;
import com.kneaf.core.exceptions.NativeLibraryException;
import com.kneaf.core.exceptions.AsyncProcessingException;
import com.kneaf.core.exceptions.OptimizedProcessingException;

public class RustPerformance {
    private RustPerformance() {}

    // Constants for request types
    private static final String ENTITIES_KEY = "entities";
    private static final String ITEMS_KEY = "items";
    private static final String MOBS_KEY = "mobs";
    private static final String BLOCKS_KEY = "blocks";
    private static final String PLAYERS_KEY = "players";
    private static final String BINARY_FALLBACK_MESSAGE = "Binary protocol failed, falling back to JSON: {}";

    // Async processing executor
    private static final AsyncProcessor asyncProcessor;
    
    static {
        AsyncProcessor.AsyncConfig config = new AsyncProcessor.AsyncConfig();
        config.processorName("RustPerformance")
              .enableLogging(true)
              .enableMetrics(true);
        asyncProcessor = AsyncProcessor.create(config);
    }

    // JNI call batching configuration - optimized for server performance
    private static final int BATCH_SIZE = NativeBridge.getOptimalBatchSize(100); // Use optimized batch size
    private static final long BATCH_TIMEOUT_MS = 50; // Increased timeout for larger batches
    private static final ConcurrentLinkedQueue<BatchRequest> pendingRequests = new ConcurrentLinkedQueue<>();
    private static volatile boolean batchProcessorRunning = false;
    private static final Object batchLock = new Object();

    // Batch request wrapper
    private static class BatchRequest {
        final String type;
        final Object data;
        final CompletableFuture<Object> future;

        BatchRequest(String type, Object data, CompletableFuture<Object> future) {
            this.type = type;
            this.data = data;
            this.future = future;
        }
    }


    // Connection Pooling (for future database interactions)
    private static final ConcurrentLinkedQueue<Connection> connectionPool = new ConcurrentLinkedQueue<>();
    private static final int MAX_CONNECTIONS = 10;
    private static volatile boolean connectionPoolInitialized = false;

    public static Connection acquireConnection() throws SQLException {
        if (!connectionPoolInitialized) {
            initializeConnectionPool();
        }

        Connection conn = connectionPool.poll();
        if (conn != null) {
            try {
                if (!conn.isClosed()) {
                    return conn;
                }
            } catch (SQLException e) {
                // Connection is invalid, continue to create new one
            }
        }

        // Create new connection if pool is empty or invalid
        return createNewConnection();
    }

    public static void releaseConnection(Connection conn) {
        if (conn != null && connectionPool.size() < MAX_CONNECTIONS) {
            try {
                if (!conn.isClosed()) {
                    if (!conn.getAutoCommit()) {
                        conn.rollback();
                    }
                    connectionPool.offer(conn);
                    return;
                }
            } catch (SQLException e) {
                KneafCore.LOGGER.warn("Failed to release connection: {}", e.getMessage());
            }
        }

        // Close invalid or excess connections
        if (conn != null) {
            try {
                conn.close();
            } catch (SQLException e) {
                // Ignore
            }
        }
    }

    private static synchronized void initializeConnectionPool() {
        if (connectionPoolInitialized) return;

        try {
            for (int i = 0; i < 5; i++) { // Start with 5 connections
                connectionPool.offer(createNewConnection());
            }
            connectionPoolInitialized = true;
            KneafCore.LOGGER.info("Database connection pool initialized");
        } catch (Exception e) {
            KneafCore.LOGGER.warn("Failed to initialize connection pool: {}", e.getMessage());
        }
    }

    private static Connection createNewConnection() throws SQLException {
        // Database connection creation is not configured in this build. Throw a clear SQLException so callers
        // are aware that DB-backed features are disabled unless configured by the server operator.
        throw new SQLException("Database connection not configured. Configure DB or remove DB usage.");
    }

    private static long tickCount = 0;
    // Metrics
    private static double currentTPS = 20.0;
    private static long totalEntitiesProcessed = 0;
    private static long totalMobsProcessed = 0;
    private static long totalBlocksProcessed = 0;
    private static long totalMerged = 0;
    private static long totalDespawned = 0;

    public static void setCurrentTPS(double currentTPS) {
        RustPerformance.currentTPS = currentTPS;
    }

    private static final String TICK_COUNT_KEY = "tickCount";
    private static final Gson gson = new Gson();
    private static volatile boolean nativeAvailable = false;

    static {
        try {
            // Use NativeLibraryLoader for robust loading with fallbacks
            if (NativeLibraryLoader.loadNativeLibrary()) {
                nativeAvailable = true;
                KneafCore.LOGGER.info("Successfully loaded rustperf native library");
            } else {
                nativeAvailable = false;
                KneafCore.LOGGER.info("rustperf native library not loaded via NativeLibraryLoader");
            }
        } catch (Exception e) {
            KneafCore.LOGGER.warn("Unexpected error loading rustperf native library: {}", e.getMessage());
            nativeAvailable = false;
        } catch (Throwable t) {
            // Catch any other throwable to prevent ExceptionInInitializerError
            KneafCore.LOGGER.warn("Critical error loading rustperf native library: {}", t.getMessage());
            nativeAvailable = false;
        }
    }

    static {
        KneafCore.LOGGER.info("Initializing RustPerformance native library - secondary initializer");
        // Only proceed if library is not already loaded by the first initializer
        if (!nativeAvailable) {
            try {
                // Try additional loading strategies if the first initializer failed
                if (NativeLibraryLoader.loadNativeLibrary()) {
                    nativeAvailable = true;
                    KneafCore.LOGGER.info("Successfully loaded rustperf native library via secondary initializer");
                } else {
                    KneafCore.LOGGER.warn("Native library not available via secondary initializer");
                    nativeAvailable = false;
                }
            } catch (Exception e) {
                KneafCore.LOGGER.warn("Failed to initialize Rust native library in secondary initializer: {}. Native optimizations disabled.", e.getMessage());
                nativeAvailable = false;
            } catch (Throwable t) {
                // Catch any other throwable to prevent ExceptionInInitializerError
                KneafCore.LOGGER.warn("Critical error in secondary initializer: {}. Native optimizations disabled.", t.getMessage());
                nativeAvailable = false;
            }
        } else {
            KneafCore.LOGGER.info("Native library already loaded, skipping secondary initialization");
        }
    }

    private static void ensureNativeAvailable() {
        if (!nativeAvailable) throw new RustPerformanceException("Rust native library is not available");
    }

    private static void tryLoadNative(Path tempFile) {
        try {
            System.load(tempFile.toAbsolutePath().toString());
            nativeAvailable = true;
            KneafCore.LOGGER.info("Successfully loaded native library");
        } catch (Exception e) {
            KneafCore.LOGGER.warn("Failed to load native library binary: {}. Native optimizations disabled.", e.getMessage());
            nativeAvailable = false;
        }
    }

    // Called from native Rust code via JNI to forward native logs into the server logger.
    // Signature matches: public static void logFromNative(String level, String msg)
    public static void logFromNative(String level, String msg) {
        if (level == null) level = "INFO";
        if (msg == null) msg = "";
        switch (level.toUpperCase()) {
            case "TRACE": KneafCore.LOGGER.trace(msg); break;
            case "DEBUG": KneafCore.LOGGER.debug(msg); break;
            case "WARN":  KneafCore.LOGGER.warn(msg);  break;
            case "ERROR": KneafCore.LOGGER.error(msg); break;
            default:       KneafCore.LOGGER.info(msg);  break;
        }
    }

    // Redirect native stderr (where Rust's eprintln! writes) into the server logger.
    private static volatile boolean nativeErrRedirectInstalled = false;

    private static void installNativeErrRedirector() {
        if (nativeErrRedirectInstalled) return;
        try {
            OutputStream os = new java.io.OutputStream() {
                private final java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
                @Override
                public synchronized void write(int b) throws java.io.IOException {
                    buf.write(b);
                    if (b == '\n') flush();
                }
                @Override
                public synchronized void flush() throws java.io.IOException {
                    String s = buf.toString("UTF-8");
                    if (s.endsWith("\n")) s = s.substring(0, s.length()-1);
                    if (!s.isEmpty()) {
                        // Tag native logs for easy filtering
                        KneafCore.LOGGER.error("[rust] {}", s);
                    }
                    buf.reset();
                }
            };

            java.io.PrintStream ps = new java.io.PrintStream(os, true, "UTF-8");
            System.setErr(ps);
            nativeErrRedirectInstalled = true;
            KneafCore.LOGGER.info("Installed native stderr redirector to KneafCore.LOGGER");
        } catch (Throwable t) {
            KneafCore.LOGGER.warn("Failed to install native stderr redirector: {}", t.getMessage());
        }
    }

    // Batch processing methods - optimized with NativeBridge
    private static void startBatchProcessor() {
        synchronized (batchLock) {
            if (batchProcessorRunning) return;
            batchProcessorRunning = true;
            
            CompletableFuture.runAsync(() -> {
                while (batchProcessorRunning) {
                    try {
                        processBatchOptimized();
                        Thread.sleep(5); // Reduced delay for better throughput
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    } catch (OptimizedProcessingException e) {
                        KneafCore.LOGGER.error("Optimized processing error in batch processor: {}", e.getMessage(), e);
                    } catch (Exception e) {
                        KneafCore.LOGGER.error("Unexpected error in batch processor", e);
                    }
                }
            });
        }
    }
    
    /**
     * Optimized batch processing using NativeBridge
     */
    private static void processBatchOptimized() {
        List<BatchRequest> batch = collectBatch();
        
        if (batch.isEmpty()) return;
        
        // Use NativeBridge for optimized batch processing if available
        if (nativeAvailable && batch.size() >= NativeBridge.getOptimalBatchSize(1)) {
            try {
                processBatchWithNativeBridge(batch);
                return;
            } catch (OptimizedProcessingException e) {
                KneafCore.LOGGER.warn("NativeBridge batch processing failed, falling back to regular processing: {}", e.getMessage());
                // Fall through to regular processing
            }
        }
        
        // Regular batch processing
        Map<String, List<BatchRequest>> batchedByType = new HashMap<>();
        for (BatchRequest req : batch) {
            batchedByType.computeIfAbsent(req.type, k -> new ArrayList<>()).add(req);
        }
        processBatchedRequests(batchedByType);
    }
    
    /**
     * Process batch using optimized NativeBridge
     */
    private static void processBatchWithNativeBridge(List<BatchRequest> batch) throws OptimizedProcessingException {
        Map<String, List<BatchRequest>> batchedByType = new HashMap<>();
        for (BatchRequest req : batch) {
            batchedByType.computeIfAbsent(req.type, k -> new ArrayList<>()).add(req);
        }
        
        // Process each type batch using NativeBridge
        for (Map.Entry<String, List<BatchRequest>> entry : batchedByType.entrySet()) {
            String type = entry.getKey();
            List<BatchRequest> typeBatch = entry.getValue();
            
            try {
                switch (type) {
                    case ENTITIES_KEY:
                        processEntityBatchOptimized(typeBatch);
                        break;
                    case ITEMS_KEY:
                        processItemBatchOptimized(typeBatch);
                        break;
                    case MOBS_KEY:
                        processMobBatchOptimized(typeBatch);
                        break;
                    case BLOCKS_KEY:
                        processBlockBatchOptimized(typeBatch);
                        break;
                    default:
                        // Fallback to individual processing
                        for (BatchRequest req : typeBatch) {
                            req.future.complete(processIndividualRequest());
                        }
                }
            } catch (OptimizedProcessingException e) {
                KneafCore.LOGGER.error("Error processing {} batch of size {} with NativeBridge", type, typeBatch.size(), e);
                // Complete all futures with exception
                for (BatchRequest req : typeBatch) {
                    req.future.completeExceptionally(e);
                }
            }
        }
    }
    
    private static void processEntityBatchOptimized(List<BatchRequest> batch) throws OptimizedProcessingException {
        if (batch.isEmpty()) return;
        
        try {
            // Pre-size collections to avoid resizing
            int totalEntities = 0;
            int totalPlayers = 0;
            for (BatchRequest req : batch) {
                Map<String, Object> data = (Map<String, Object>) req.data;
                List<EntityData> entities = (List<EntityData>) data.get(ENTITIES_KEY);
                List<PlayerData> players = (List<PlayerData>) data.get(PLAYERS_KEY);
                totalEntities += entities.size();
                totalPlayers += players.size();
            }
            
            // Extract data from all requests in batch
            List<EntityData> allEntities = new ArrayList<>(totalEntities);
            List<PlayerData> allPlayers = new ArrayList<>(totalPlayers);
            
            for (BatchRequest req : batch) {
                Map<String, Object> data = (Map<String, Object>) req.data;
                List<EntityData> entities = (List<EntityData>) data.get(ENTITIES_KEY);
                List<PlayerData> players = (List<PlayerData>) data.get(PLAYERS_KEY);
                
                allEntities.addAll(entities);
                allPlayers.addAll(players);
            }
            
            // Process combined data using optimized method
            List<Long> results = processEntitiesDirect(allEntities, allPlayers);
            
            // Create a Set for faster lookup
            Set<Long> resultSet = new HashSet<>(results);
            
            // Distribute results back to individual futures
            for (BatchRequest req : batch) {
                Map<String, Object> data = (Map<String, Object>) req.data;
                List<EntityData> entities = (List<EntityData>) data.get(ENTITIES_KEY);
                
                // Find results for this request
                List<Long> requestResults = new ArrayList<>();
                for (EntityData entity : entities) {
                    if (resultSet.contains(entity.id())) {
                        requestResults.add(entity.id());
                    }
                }
                
                req.future.complete(requestResults);
            }
        } catch (Exception e) {
            throw OptimizedProcessingException.batchProcessingError("processEntityBatchOptimized",
                "Failed to process entity batch of size " + batch.size(), e);
        }
    }
    
    private static void processItemBatchOptimized(List<BatchRequest> batch) throws OptimizedProcessingException {
        if (batch.isEmpty()) return;
        
        try {
            List<ItemEntityData> allItems = new ArrayList<>();
            for (BatchRequest req : batch) {
                @SuppressWarnings("unchecked")
                List<ItemEntityData> items = (List<ItemEntityData>) req.data;
                allItems.addAll(items);
            }
            
            ItemProcessResult result = processItemEntitiesDirect(allItems);
            
            // For simplicity, distribute results equally among batch requests
            for (BatchRequest req : batch) {
                req.future.complete(result);
            }
        } catch (Exception e) {
            throw OptimizedProcessingException.batchProcessingError("processItemBatchOptimized",
                "Failed to process item batch of size " + batch.size(), e);
        }
    }
    
    private static void processMobBatchOptimized(List<BatchRequest> batch) throws OptimizedProcessingException {
        if (batch.isEmpty()) return;
        
        try {
            List<MobData> allMobs = new ArrayList<>();
            for (BatchRequest req : batch) {
                @SuppressWarnings("unchecked")
                List<MobData> mobs = (List<MobData>) req.data;
                allMobs.addAll(mobs);
            }
            
            MobProcessResult result = processMobAIDirect(allMobs);
            
            // Distribute results equally among batch requests
            for (BatchRequest req : batch) {
                req.future.complete(result);
            }
        } catch (Exception e) {
            throw OptimizedProcessingException.batchProcessingError("processMobBatchOptimized",
                "Failed to process mob batch of size " + batch.size(), e);
        }
    }
    
    private static void processBlockBatchOptimized(List<BatchRequest> batch) throws OptimizedProcessingException {
        if (batch.isEmpty()) return;
        
        try {
            List<BlockEntityData> allBlocks = new ArrayList<>();
            for (BatchRequest req : batch) {
                @SuppressWarnings("unchecked")
                List<BlockEntityData> blocks = (List<BlockEntityData>) req.data;
                allBlocks.addAll(blocks);
            }
            
            List<Long> results = getBlockEntitiesToTickDirect(allBlocks);
            
            // Distribute results equally among batch requests
            for (BatchRequest req : batch) {
                req.future.complete(results);
            }
        } catch (Exception e) {
            throw OptimizedProcessingException.batchProcessingError("processBlockBatchOptimized",
                "Failed to process block batch of size " + batch.size(), e);
        }
    }

    private static List<BatchRequest> collectBatch() {
        List<BatchRequest> batch = new ArrayList<>();
        BatchRequest request;
        
        // Collect batch with timeout
        long startTime = System.currentTimeMillis();
        boolean continueCollecting = true;
        while (continueCollecting &&
               batch.size() < BATCH_SIZE &&
               (System.currentTimeMillis() - startTime) < BATCH_TIMEOUT_MS) {
            request = pendingRequests.poll();
            if (request != null) {
                batch.add(request);
            } else {
                // No more requests, break if we have some or wait a bit
                if (!batch.isEmpty()) {
                    continueCollecting = false;
                } else {
                    try {
                        Thread.sleep(5); // Small wait for new requests
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        continueCollecting = false;
                    }
                }
            }
        }
        
        return batch;
    }

    private static void processBatch() {
        List<BatchRequest> batch = collectBatch();

        if (batch.isEmpty()) return;

        // Process batch based on type
        Map<String, List<BatchRequest>> batchedByType = new HashMap<>();
        for (BatchRequest req : batch) {
            batchedByType.computeIfAbsent(req.type, k -> new ArrayList<>()).add(req);
        }

        processBatchedRequests(batchedByType);
    }

    private static void processBatchedRequests(Map<String, List<BatchRequest>> batchedByType) {
        // Process each type batch
        for (Map.Entry<String, List<BatchRequest>> entry : batchedByType.entrySet()) {
            String type = entry.getKey();
            List<BatchRequest> typeBatch = entry.getValue();
            
            try {
                switch (type) {
                    case ENTITIES_KEY:
                        processEntityBatch(typeBatch);
                        break;
                    case ITEMS_KEY:
                        processItemBatch(typeBatch);
                        break;
                    case MOBS_KEY:
                        processMobBatch(typeBatch);
                        break;
                    case BLOCKS_KEY:
                        processBlockBatch(typeBatch);
                        break;
                    default:
                        // Fallback to individual processing
                        for (BatchRequest req : typeBatch) {
                            req.future.complete(processIndividualRequest());
                        }
                }
            } catch (Exception e) {
                KneafCore.LOGGER.error("Error processing {} batch of size {}", type, typeBatch.size(), e);
                // Complete all futures with exception
                for (BatchRequest req : typeBatch) {
                    req.future.completeExceptionally(e);
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void processEntityBatch(List<BatchRequest> batch) {
        if (batch.isEmpty()) return;
        
        // Pre-size collections to avoid resizing
        int totalEntities = 0;
        int totalPlayers = 0;
        for (BatchRequest req : batch) {
            Map<String, Object> data = (Map<String, Object>) req.data;
            List<EntityData> entities = (List<EntityData>) data.get(ENTITIES_KEY);
            List<PlayerData> players = (List<PlayerData>) data.get(PLAYERS_KEY);
            totalEntities += entities.size();
            totalPlayers += players.size();
        }
        
        // Extract data from all requests in batch
        List<EntityData> allEntities = new ArrayList<>(totalEntities);
        List<PlayerData> allPlayers = new ArrayList<>(totalPlayers);
        Map<Integer, List<CompletableFuture<Object>>> resultMapping = HashMap.newHashMap(totalEntities);
        
        for (int i = 0; i < batch.size(); i++) {
            BatchRequest req = batch.get(i);
            Map<String, Object> data = (Map<String, Object>) req.data;
            List<EntityData> entities = (List<EntityData>) data.get(ENTITIES_KEY);
            List<PlayerData> players = (List<PlayerData>) data.get(PLAYERS_KEY);
            
            allEntities.addAll(entities);
            allPlayers.addAll(players);
            
            // Map result indices back to futures
            int startIdx = allEntities.size() - entities.size();
            int endIdx = allEntities.size();
            for (int j = startIdx; j < endIdx; j++) {
                resultMapping.computeIfAbsent(j, k -> new ArrayList<>(2)).add(req.future);
            }
        }

        // Process combined data
        List<Long> results = processEntitiesDirect(allEntities, allPlayers);
        
        // Create a Set for faster lookup
        Set<Long> resultSet = new HashSet<>(results);
        
        // Distribute results back to individual futures
        for (int i = 0; i < batch.size(); i++) {
            BatchRequest req = batch.get(i);
            Map<String, Object> data = (Map<String, Object>) req.data;
            List<EntityData> entities = (List<EntityData>) data.get(ENTITIES_KEY);
            
            // Find results for this request
            List<Long> requestResults = new ArrayList<>();
            for (EntityData entity : entities) {
                if (resultSet.contains(entity.id())) {
                    requestResults.add(entity.id());
                }
            }
            
            req.future.complete(requestResults);
        }
    }

    private static void processItemBatch(List<BatchRequest> batch) {
        if (batch.isEmpty()) return;
        
        List<ItemEntityData> allItems = new ArrayList<>();
        for (BatchRequest req : batch) {
            @SuppressWarnings("unchecked")
            List<ItemEntityData> items = (List<ItemEntityData>) req.data;
            allItems.addAll(items);
        }
        
        ItemProcessResult result = processItemEntitiesDirect(allItems);
        
        // For simplicity, distribute results equally among batch requests
        for (BatchRequest req : batch) {
            req.future.complete(result);
        }
    }

    private static void processMobBatch(List<BatchRequest> batch) {
        if (batch.isEmpty()) return;
        
        List<MobData> allMobs = new ArrayList<>();
        for (BatchRequest req : batch) {
            @SuppressWarnings("unchecked")
            List<MobData> mobs = (List<MobData>) req.data;
            allMobs.addAll(mobs);
        }
        
        MobProcessResult result = processMobAIDirect(allMobs);
        
        // Distribute results equally among batch requests
        for (BatchRequest req : batch) {
            req.future.complete(result);
        }
    }

    private static void processBlockBatch(List<BatchRequest> batch) {
        if (batch.isEmpty()) return;
        
        List<BlockEntityData> allBlocks = new ArrayList<>();
        for (BatchRequest req : batch) {
            @SuppressWarnings("unchecked")
            List<BlockEntityData> blocks = (List<BlockEntityData>) req.data;
            allBlocks.addAll(blocks);
        }
        
        List<Long> results = getBlockEntitiesToTickDirect(allBlocks);
        
        // Distribute results equally among batch requests
        for (BatchRequest req : batch) {
            req.future.complete(results);
        }
    }

    private static Object processIndividualRequest() {
        // Fallback for unhandled types
        return null;
    }

    // Submit batch request helper
    private static <T> T submitBatchRequest(String type, Object data) {
        CompletableFuture<Object> future = new CompletableFuture<>();
        BatchRequest request = new BatchRequest(type, data, future);
        
        pendingRequests.offer(request);
        
        // Start batch processor if not running
        if (!batchProcessorRunning) {
            startBatchProcessor();
        }
        
        try {
            @SuppressWarnings("unchecked")
            T result = (T) future.get(5, TimeUnit.SECONDS); // Timeout to prevent hanging
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw AsyncProcessingException.batchRequestInterrupted(type, e);
        } catch (Exception e) {
            KneafCore.LOGGER.error("Batch request timeout or error for type: {}", type, e);
            // Fallback to direct processing
            return processRequestDirect(type, data);
        }
    }

    // Functional interfaces for the template method
    @FunctionalInterface
    private interface BinarySerializer<T, R> {
        java.nio.ByteBuffer serialize(T input) throws Exception;
    }
    
    @FunctionalInterface
    private interface BinaryNativeCaller {
        byte[] callNative(java.nio.ByteBuffer input) throws Exception;
    }
    
    @FunctionalInterface
    private interface BinaryDeserializer<R> {
        R deserialize(byte[] resultBytes) throws Exception;
    }
    
    @FunctionalInterface
    private interface JsonInputPreparer<T> {
        Map<String, Object> prepareInput(T input);
    }
    
    @FunctionalInterface
    private interface JsonNativeCaller {
        String callNative(String jsonInput);
    }
    
    @FunctionalInterface
    private interface JsonResultParser<R> {
        R parseResult(String jsonResult);
    }

    // Protocol processor for binary/JSON fallback patterns
    private static final ProtocolProcessor protocolProcessor = ProtocolProcessor.createAuto(nativeAvailable);
    
    // Template method for try-binary-catch-fallback-JSON pattern (replaced with ProtocolProcessor)
    private static <T, R> R processWithBinaryFallback(
            T input,
            BinarySerializer<T, R> binarySerializer,
            BinaryNativeCaller binaryNativeCaller,
            BinaryDeserializer<R> binaryDeserializer,
            JsonInputPreparer<T> jsonInputPreparer,
            JsonNativeCaller jsonNativeCaller,
            JsonResultParser<R> jsonResultParser,
            R fallbackResult,
            String operationName) {
        
        // Use ProtocolProcessor for unified binary/JSON processing
        ProtocolProcessor.ProtocolResult<R> result = protocolProcessor.processWithFallback(
            input,
            operationName,
            new ProtocolProcessor.BinarySerializer<T>() {
                @Override
                public java.nio.ByteBuffer serialize(T binaryInput) throws Exception {
                    return binarySerializer.serialize(binaryInput);
                }
            },
            new ProtocolProcessor.BinaryNativeCaller<byte[]>() {
                @Override
                public byte[] callNative(java.nio.ByteBuffer inputBuffer) throws Exception {
                    return binaryNativeCaller.callNative(inputBuffer);
                }
            },
            new ProtocolProcessor.BinaryDeserializer<R>() {
                @Override
                public R deserialize(byte[] resultBytes) throws Exception {
                    if (resultBytes != null) {
                        return binaryDeserializer.deserialize(resultBytes);
                    }
                    return fallbackResult;
                }
            },
            new ProtocolProcessor.JsonInputPreparer<T>() {
                @Override
                public Map<String, Object> prepareInput(T jsonInput) {
                    return jsonInputPreparer.prepareInput(jsonInput);
                }
            },
            new ProtocolProcessor.JsonNativeCaller<String>() {
                @Override
                public String callNative(String jsonInput) throws Exception {
                    return jsonNativeCaller.callNative(jsonInput);
                }
            },
            new ProtocolProcessor.JsonResultParser<R>() {
                @Override
                public R parseResult(String jsonResult) throws Exception {
                    return jsonResultParser.parseResult(jsonResult);
                }
            },
            fallbackResult
        );
        
        return result.getDataOrThrow();
    }

    // Direct processing fallback methods
    private static List<Long> processEntitiesDirect(List<EntityData> entities, List<PlayerData> players) {
        return processWithBinaryFallback(
            new EntityInput(entities, players),
            (input) -> ManualSerializers.serializeEntityInput(tickCount++, input.entities, input.players),
            (inputBuffer) -> processEntitiesBinaryNative(inputBuffer),
            (resultBytes) -> {
                // Try the preferred entity-format first: [len:i32][ids...]
                try {
                    java.nio.ByteBuffer resultBuffer = java.nio.ByteBuffer.wrap(resultBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN);
                    List<Long> resultList = ManualSerializers.deserializeEntityProcessResult(resultBuffer);
                    totalEntitiesProcessed += resultList.size();
                    return resultList;
                } catch (Throwable primaryEx) {
                    // Primary parse failed: attempt a tolerant fallback for tickCount-prefixed lists: [tickCount:u64][num:i32][ids...]
                    try {
                        if (resultBytes == null) throw primaryEx;
                        int len = resultBytes.length;
                        java.nio.ByteBuffer altBuf = java.nio.ByteBuffer.wrap(resultBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN);
                        if (len >= 12) {
                            long maybeTick = altBuf.getLong(0);
                            int numItems = altBuf.getInt(8);
                            // Sanity checks
                            if (numItems >= 0 && numItems <= 1_000_000 && 12 + numItems * 8 <= len) {
                                List<Long> altList = new java.util.ArrayList<>(numItems);
                                for (int i = 0; i < numItems; i++) {
                                    long id = altBuf.getLong(12 + i * 8);
                                    altList.add(id);
                                }
                                KneafCore.LOGGER.warn("Entity binary parser primary format failed ({}); used tickCount-prefixed fallback: tick={} numItems={} result_len={}", primaryEx.getMessage(), maybeTick, numItems, len);
                                totalEntitiesProcessed += altList.size();
                                return altList;
                            }
                        }
                    } catch (Throwable altEx) {
                        try {
                            String prefix = bytesPrefixHex(resultBytes, 128);
                            KneafCore.LOGGER.error("Entity binary deserialization failed (primary: {}; fallback: {}); result_len={} ; prefix={}", primaryEx.getMessage(), altEx.getMessage(), resultBytes == null ? 0 : resultBytes.length, prefix, primaryEx);
                        } catch (Throwable t2) {
                            KneafCore.LOGGER.error("Entity binary deserialization failed and prefix computation also failed: {}", t2.getMessage(), t2);
                        }
                        // Fall through to outer binary fallback
                    }
                    // Re-throw the primary exception so the outer handler logs and falls back to JSON
                    throw primaryEx;
                }
            },
            (input) -> {
                Map<String, Object> jsonInput = new HashMap<>();
                jsonInput.put(TICK_COUNT_KEY, tickCount++);
                jsonInput.put(ENTITIES_KEY, input.entities);
                jsonInput.put(PLAYERS_KEY, input.players);
                
                // Add entity config
                Map<String, Object> config = new HashMap<>();
                config.put("closeRadius", 16.0f);
                config.put("mediumRadius", 32.0f);
                config.put("closeRate", 1.0f);
                config.put("mediumRate", 0.5f);
                config.put("farRate", 0.1f);
                config.put("useSpatialPartitioning", true);
                
                // World bounds (example values)
                Map<String, Object> worldBounds = new HashMap<>();
                worldBounds.put("minX", -1000.0);
                worldBounds.put("minY", 0.0);
                worldBounds.put("minZ", -1000.0);
                worldBounds.put("maxX", 1000.0);
                worldBounds.put("maxY", 256.0);
                worldBounds.put("maxZ", 1000.0);
                config.put("worldBounds", worldBounds);
                
                config.put("quadtreeMaxEntities", 1000);
                config.put("quadtreeMaxDepth", 10);
                jsonInput.put("entityConfig", config);
                return jsonInput;
            },
            (jsonInput) -> {
                try {
                    String result = processEntitiesNative(jsonInput);
                    if (result == null) {
                        KneafCore.LOGGER.error("processEntitiesNative returned null for JSON input");
                        return "{\"error\":\"Native method returned null\"}";
                    }
                    // Validate result is not empty and contains valid JSON
                    if (result.isEmpty()) {
                        KneafCore.LOGGER.error("processEntitiesNative returned empty string");
                        return "{\"error\":\"Native method returned empty string\"}";
                    }
                    // Basic JSON validation - check if it starts with { and ends with }
                    if (!result.trim().startsWith("{") || !result.trim().endsWith("}")) {
                        KneafCore.LOGGER.error("processEntitiesNative returned invalid JSON format: {}", result.substring(0, Math.min(result.length(), 100)));
                        return "{\"error\":\"Native method returned invalid JSON format\"}";
                    }
                    return result;
                } catch (Exception e) {
                    KneafCore.LOGGER.error("Exception in processEntitiesNative: {}", e.getMessage(), e);
                    return "{\"error\":\"" + e.getMessage() + "\"}";
                }
            },
            (jsonResult) -> {
                try {
                    JsonObject result = gson.fromJson(jsonResult, JsonObject.class);
                    if (result == null) {
                        KneafCore.LOGGER.error("Failed to parse JSON result: null result object");
                        return new ArrayList<>();
                    }
                    
                    // Check for error field first
                    if (result.has("error")) {
                        String error = result.get("error").getAsString();
                        KneafCore.LOGGER.error("Rust processing returned error: {}", error);
                        return new ArrayList<>();
                    }
                    
                    if (!result.has("entitiesToTick")) {
                        KneafCore.LOGGER.error("JSON result missing 'entitiesToTick' field");
                        return new ArrayList<>();
                    }
                    
                    JsonElement entitiesElement = result.get("entitiesToTick");
                    if (entitiesElement == null || !entitiesElement.isJsonArray()) {
                        KneafCore.LOGGER.error("entitiesToTick is null or not an array");
                        return new ArrayList<>();
                    }
                    
                    JsonArray entitiesToTick = entitiesElement.getAsJsonArray();
                    List<Long> resultList = new ArrayList<>();
                    for (JsonElement e : entitiesToTick) {
                        if (e != null && e.isJsonPrimitive()) {
                            try {
                                resultList.add(e.getAsLong());
                            } catch (NumberFormatException nfe) {
                                KneafCore.LOGGER.error("Invalid entity ID in result: {}", e);
                            }
                        }
                    }
                    totalEntitiesProcessed += resultList.size();
                    return resultList;
                } catch (Exception e) {
                    KneafCore.LOGGER.error("Error parsing JSON result: {}", jsonResult, e);
                    return new ArrayList<>();
                }
            },
            new ArrayList<>(),
            "Entity processing"
        );
    }

    // Helper class for entity input
    private static class EntityInput {
        final List<EntityData> entities;
        final List<PlayerData> players;
        
        EntityInput(List<EntityData> entities, List<PlayerData> players) {
            this.entities = entities;
            this.players = players;
        }
    }

    private static ItemProcessResult processItemEntitiesDirect(List<ItemEntityData> items) {
        return processWithBinaryFallback(
            items,
            (input) -> ManualSerializers.serializeItemInput(tickCount, input),
            (inputBuffer) -> processItemEntitiesBinaryNative(inputBuffer),
            (resultBytes) -> {
                java.nio.ByteBuffer resultBuffer = java.nio.ByteBuffer.wrap(resultBytes);
                List<com.kneaf.core.data.ItemEntityData> updatedItems =
                    ManualSerializers.deserializeItemProcessResult(resultBuffer);
                
                // Convert to ItemProcessResult format
                List<Long> removeList = new ArrayList<>();
                List<ItemUpdate> updates = new ArrayList<>();
                
                for (com.kneaf.core.data.ItemEntityData item : updatedItems) {
                    if (item.count() == 0) {
                        removeList.add(item.id());
                    } else {
                        updates.add(new ItemUpdate(item.id(), item.count()));
                    }
                }
                
                totalMerged += updates.size();
                totalDespawned += removeList.size();
                return new ItemProcessResult(removeList, updates.size(), removeList.size(), updates);
            },
            (input) -> {
                Map<String, Object> jsonInput = new HashMap<>();
                jsonInput.put(ITEMS_KEY, input);
                return jsonInput;
            },
            (jsonInput) -> { try { return processItemEntitiesNative(jsonInput); } catch (Exception e) { return null; } },
            (jsonResult) -> {
                JsonObject result = gson.fromJson(jsonResult, JsonObject.class);
                JsonArray itemsToRemove = result.getAsJsonArray("items_to_remove");
                List<Long> removeList = new ArrayList<>();
                for (JsonElement e : itemsToRemove) {
                    removeList.add(e.getAsLong());
                }
                long merged = result.get("merged_count").getAsLong();
                long despawned = result.get("despawned_count").getAsLong();
                JsonArray itemUpdatesArray = result.getAsJsonArray("item_updates");
                List<ItemUpdate> updates = new ArrayList<>();
                for (JsonElement e : itemUpdatesArray) {
                    JsonObject obj = e.getAsJsonObject();
                    long id = obj.get("id").getAsLong();
                    int newCount = obj.get("new_count").getAsInt();
                    updates.add(new ItemUpdate(id, newCount));
                }
                totalMerged += merged;
                totalDespawned += despawned;
                return new ItemProcessResult(removeList, merged, despawned, updates);
            },
            new ItemProcessResult(new ArrayList<>(), 0, 0, new ArrayList<>()),
            "Item entity processing"
        );
    }

    // Legacy binary and JSON methods - kept for compatibility but now use template method internally
    private static ItemProcessResult processItemEntitiesBinary(List<ItemEntityData> items) {
        try {
            // Serialize to FlatBuffers binary format
            java.nio.ByteBuffer inputBuffer = ManualSerializers.serializeItemInput(
                tickCount, items);
            
            // Call binary native method (returns byte[] from Rust)
            byte[] resultBytes = processItemEntitiesBinaryNative(inputBuffer);

            if (resultBytes != null) {
                java.nio.ByteBuffer resultBuffer = java.nio.ByteBuffer.wrap(resultBytes);
                // Deserialize result
                List<com.kneaf.core.data.ItemEntityData> updatedItems =
                    ManualSerializers.deserializeItemProcessResult(resultBuffer);
                
                // Convert to ItemProcessResult format
                List<Long> removeList = new ArrayList<>();
                List<ItemUpdate> updates = new ArrayList<>();
                
                for (com.kneaf.core.data.ItemEntityData item : updatedItems) {
                    if (item.count() == 0) {
                        removeList.add(item.id());
                    } else {
                        updates.add(new ItemUpdate(item.id(), item.count()));
                    }
                }
                
                totalMerged += updates.size();
                totalDespawned += removeList.size();
                return new ItemProcessResult(removeList, updates.size(), removeList.size(), updates);
            }
        } catch (Exception binaryEx) {
            KneafCore.LOGGER.debug(BINARY_FALLBACK_MESSAGE, binaryEx.getMessage());
        }
        return null;
    }

    private static ItemProcessResult processItemEntitiesJson(List<ItemEntityData> items) {
        Map<String, Object> input = new HashMap<>();
        input.put(ITEMS_KEY, items);
        String jsonInput = gson.toJson(input);
        String jsonResult = null;
        if (jsonResult != null) {
            JsonObject result = gson.fromJson(jsonResult, JsonObject.class);
            JsonArray itemsToRemove = result.getAsJsonArray("items_to_remove");
            List<Long> removeList = new ArrayList<>();
            for (JsonElement e : itemsToRemove) {
                removeList.add(e.getAsLong());
            }
            long merged = result.get("merged_count").getAsLong();
            long despawned = result.get("despawned_count").getAsLong();
            JsonArray itemUpdatesArray = result.getAsJsonArray("item_updates");
            List<ItemUpdate> updates = new ArrayList<>();
            for (JsonElement e : itemUpdatesArray) {
                JsonObject obj = e.getAsJsonObject();
                long id = obj.get("id").getAsLong();
                int newCount = obj.get("new_count").getAsInt();
                updates.add(new ItemUpdate(id, newCount));
            }
            totalMerged += merged;
            totalDespawned += despawned;
            return new ItemProcessResult(removeList, merged, despawned, updates);
        }
        return null;
    }

    private static MobProcessResult processMobAIDirect(List<MobData> mobs) {
        return processWithBinaryFallback(
            mobs,
            (input) -> ManualSerializers.serializeMobInput(tickCount, input),
            (inputBuffer) -> processMobAiBinaryNative(inputBuffer),
            (resultBytes) -> {
                java.nio.ByteBuffer resultBuffer = java.nio.ByteBuffer.wrap(resultBytes);
                List<com.kneaf.core.data.MobData> updatedMobs =
                    ManualSerializers.deserializeMobProcessResult(resultBuffer);
                
                // For now, assume all returned mobs need AI simplification
                List<Long> simplifyList = new ArrayList<>();
                for (com.kneaf.core.data.MobData mob : updatedMobs) {
                    simplifyList.add(mob.id());
                }
                
                totalMobsProcessed += mobs.size();
                return new MobProcessResult(new ArrayList<>(), simplifyList);
            },
            (input) -> {
                Map<String, Object> jsonInput = new HashMap<>();
                jsonInput.put(TICK_COUNT_KEY, tickCount);
                jsonInput.put("mobs", input);
                return jsonInput;
            },
            (jsonInput) -> { try { return processMobAiNative(jsonInput); } catch (Exception e) { return null; } },
            (jsonResult) -> {
                JsonObject result = gson.fromJson(jsonResult, JsonObject.class);
                JsonArray disableAi = result.getAsJsonArray("mobs_to_disable_ai");
                JsonArray simplifyAi = result.getAsJsonArray("mobs_to_simplify_ai");
                List<Long> disableList = new ArrayList<>();
                List<Long> simplifyList = new ArrayList<>();
                for (JsonElement e : disableAi) {
                    disableList.add(e.getAsLong());
                }
                for (JsonElement e : simplifyAi) {
                    simplifyList.add(e.getAsLong());
                }
                totalMobsProcessed += mobs.size();
                return new MobProcessResult(disableList, simplifyList);
            },
            new MobProcessResult(new ArrayList<>(), new ArrayList<>()),
            "Mob AI processing"
        );
    }

    private static List<Long> getBlockEntitiesToTickDirect(List<BlockEntityData> blockEntities) {
        // Use binary protocol if available, fallback to JSON
        if (nativeAvailable) {
            try {
                // Serialize to FlatBuffers binary format
                java.nio.ByteBuffer inputBuffer = ManualSerializers.serializeBlockInput(
                    tickCount++, blockEntities);
                
                // Call binary native method (returns byte[] from Rust)
                byte[] resultBytes = processBlockEntitiesBinaryNative(inputBuffer);

                if (resultBytes != null) {
                        // Binary protocol returned bytes (not currently deserialized here) -
                        // for now return all block entities as the binary protocol doesn't return a specific list
                    // doesn't return a specific list of entities to tick
                    List<Long> resultList = new ArrayList<>();
                    for (BlockEntityData block : blockEntities) {
                        resultList.add(block.id());
                    }
                    totalBlocksProcessed += resultList.size();
                    return resultList;
                }
            } catch (Exception binaryEx) {
                KneafCore.LOGGER.debug(BINARY_FALLBACK_MESSAGE, binaryEx.getMessage());
                // Fall through to JSON fallback
            }
        }
        
        // JSON fallback
        Map<String, Object> input = new HashMap<>();
        input.put(TICK_COUNT_KEY, tickCount++);
        input.put("block_entities", blockEntities);
        String jsonInput = gson.toJson(input);
        String jsonResult;
        try {
            jsonResult = processBlockEntitiesNative(jsonInput);
        } catch (Exception e) {
            jsonResult = null;
        }
        if (jsonResult != null) {
            JsonObject result = gson.fromJson(jsonResult, JsonObject.class);
            JsonArray entitiesToTick = result.getAsJsonArray("block_entities_to_tick");
            List<Long> resultList = new ArrayList<>();
            for (JsonElement e : entitiesToTick) {
                resultList.add(e.getAsLong());
            }
            totalBlocksProcessed += resultList.size();
            return resultList;
        }
        
        // Fallback: return all
        List<Long> all = new ArrayList<>();
        for (BlockEntityData e : blockEntities) {
            all.add(e.id());
        }
        return all;
    }

    @SuppressWarnings("unchecked")
    private static <T> T processRequestDirect(String type, Object data) {
        switch (type) {
            case ENTITIES_KEY:
                Map<String, Object> entityData = (Map<String, Object>) data;
                return (T) processEntitiesDirect(
                    (List<EntityData>) entityData.get(ENTITIES_KEY),
                    (List<PlayerData>) entityData.get(PLAYERS_KEY)
                );
            case ITEMS_KEY:
                return (T) processItemEntitiesDirect((List<ItemEntityData>) data);
            case MOBS_KEY:
                return (T) processMobAIDirect((List<MobData>) data);
            case BLOCKS_KEY:
                return (T) getBlockEntitiesToTickDirect((List<BlockEntityData>) data);
            default:
                return null;
        }
    }

    // Native methods - JSON (legacy)
    private static native String processEntitiesNative(String jsonInput);
    private static native String processItemEntitiesNative(String jsonInput);
    private static native String processMobAiNative(String jsonInput);
    private static native String processBlockEntitiesNative(String jsonInput);
    
    // Native methods - Binary FlatBuffers (new)
    // NOTE: Rust JNI currently returns jbyteArray (copied byte[]). Match that by returning byte[] here
    private static native byte[] processEntitiesBinaryNative(java.nio.ByteBuffer input);
    private static native byte[] processItemEntitiesBinaryNative(java.nio.ByteBuffer input);
    private static native byte[] processMobAiBinaryNative(java.nio.ByteBuffer input);
    private static native byte[] processBlockEntitiesBinaryNative(java.nio.ByteBuffer input);
    // numeric utilities exposed from Rust
    public static native String parallelSumNative(String arrJson);
    public static native String matrixMultiplyNative(String aJson, String bJson);
    public static native String getMemoryStatsNative();
    public static native String getCpuStatsNative();
    private static native int preGenerateNearbyChunksNative(int centerX, int centerZ, int radius);
    private static native boolean isChunkGeneratedNative(int x, int z);
    private static native long getGeneratedChunkCountNative();
    // New binary-native methods
    public static native String blake3FromByteBuffer(java.nio.ByteBuffer buf);
    public static native java.nio.ByteBuffer generateFloatBufferNative(long rows, long cols);
    public static native void freeFloatBufferNative(java.nio.ByteBuffer buf);
    // New: allocate and return both buffer + shape
    public static native NativeFloatBufferAllocation generateFloatBufferWithShapeNative(long rows, long cols);

    // Worker metrics (exposed from native worker)
    public static native int nativeGetWorkerQueueDepth();
    public static native double nativeGetWorkerAvgProcessingMs();

    // Helper to produce a short hex prefix of a byte array for logging
    private static String bytesPrefixHex(byte[] data, int maxBytes) {
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

    public static List<Long> getEntitiesToTick(List<EntityData> entities, List<PlayerData> players) {
        return submitBatchRequest(ENTITIES_KEY, Map.of(ENTITIES_KEY, entities, PLAYERS_KEY, players, TICK_COUNT_KEY, tickCount++));
    }

    public static ItemProcessResult processItemEntities(List<ItemEntityData> items) {
        try {
            // Use binary protocol if available, fallback to JSON
            if (nativeAvailable) {
                ItemProcessResult binaryResult = processItemEntitiesBinary(items);
                if (binaryResult != null) {
                    return binaryResult;
                }
            }
            
            // JSON fallback
            ItemProcessResult jsonResult = processItemEntitiesJson(items);
            if (jsonResult != null) {
                return jsonResult;
            }
        } catch (Exception e) {
            KneafCore.LOGGER.error("Error calling Rust for item processing: {}", e.getMessage(), e);
        }
        // Fallback: no optimization
        return new ItemProcessResult(new ArrayList<>(), 0, 0, new ArrayList<>());
    }

    public static MobProcessResult processMobAI(List<MobData> mobs) {
        try {
            // Use binary protocol if available, fallback to JSON
            if (nativeAvailable) {
                MobProcessResult binaryResult = processMobAIBinary(mobs);
                if (binaryResult != null) {
                    return binaryResult;
                }
            }
            
            // JSON fallback
            MobProcessResult jsonResult = processMobAIJson(mobs);
            if (jsonResult != null) {
                return jsonResult;
            }
        } catch (Exception e) {
            KneafCore.LOGGER.error("Error calling Rust for mob AI processing: {}", e.getMessage(), e);
        }
        // Fallback: no optimization
        return new MobProcessResult(new ArrayList<>(), new ArrayList<>());
    }

    private static MobProcessResult processMobAIBinary(List<MobData> mobs) {
        try {
            // Serialize to ByteBuffer binary format
            java.nio.ByteBuffer inputBuffer = ManualSerializers.serializeMobInput(
                tickCount, mobs);
            
            // Call binary native method (returns byte[] from Rust)
            byte[] resultBytes = processMobAiBinaryNative(inputBuffer);

            if (resultBytes != null) {
                java.nio.ByteBuffer resultBuffer = java.nio.ByteBuffer.wrap(resultBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN);
                // Deserialize result
                List<com.kneaf.core.data.MobData> updatedMobs =
                    ManualSerializers.deserializeMobProcessResult(resultBuffer);
                
                // For now, assume all returned mobs need AI simplification
                List<Long> simplifyList = new ArrayList<>();
                for (com.kneaf.core.data.MobData mob : updatedMobs) {
                    simplifyList.add(mob.id());
                }
                
                totalMobsProcessed += mobs.size();
                return new MobProcessResult(new ArrayList<>(), simplifyList);
            }
        } catch (Exception binaryEx) {
            KneafCore.LOGGER.debug(BINARY_FALLBACK_MESSAGE, binaryEx.getMessage());
        }
        return null;
    }

    private static MobProcessResult processMobAIJson(List<MobData> mobs) {
        Map<String, Object> input = new HashMap<>();
        input.put(TICK_COUNT_KEY, tickCount);
        input.put("mobs", mobs);
        String jsonInput = gson.toJson(input);
        String jsonResult = null;
        if (jsonResult != null) {
            JsonObject result = gson.fromJson(jsonResult, JsonObject.class);
            JsonArray disableAi = result.getAsJsonArray("mobs_to_disable_ai");
            JsonArray simplifyAi = result.getAsJsonArray("mobs_to_simplify_ai");
            List<Long> disableList = new ArrayList<>();
            List<Long> simplifyList = new ArrayList<>();
            for (JsonElement e : disableAi) {
                disableList.add(e.getAsLong());
            }
            for (JsonElement e : simplifyAi) {
                simplifyList.add(e.getAsLong());
            }
            totalMobsProcessed += mobs.size();
            return new MobProcessResult(disableList, simplifyList);
        }
        return null;
    }

    public static List<Long> getBlockEntitiesToTick(List<BlockEntityData> blockEntities) {
        try {
            // Use binary protocol if available, fallback to JSON
            if (nativeAvailable) {
                List<Long> binaryResult = getBlockEntitiesToTickBinary(blockEntities);
                if (!binaryResult.isEmpty()) {
                    return binaryResult;
                }
            }
            
            // JSON fallback
            List<Long> jsonResult = getBlockEntitiesToTickJson(blockEntities);
            if (!jsonResult.isEmpty()) {
                return jsonResult;
            }
        } catch (Exception e) {
            KneafCore.LOGGER.error("Error calling Rust for block entity processing: {}", e.getMessage(), e);
        }
        // Fallback: return all
        List<Long> all = new ArrayList<>();
        for (BlockEntityData e : blockEntities) {
            all.add(e.id());
        }
        return all;
    }

    private static List<Long> getBlockEntitiesToTickBinary(List<BlockEntityData> blockEntities) {
        try {
                // Serialize to ByteBuffer binary format
                java.nio.ByteBuffer inputBuffer = ManualSerializers.serializeBlockInput(
                    tickCount++, blockEntities);
            
            // Call binary native method (returns byte[] from Rust)
            byte[] resultBytes = processBlockEntitiesBinaryNative(inputBuffer);

            if (resultBytes != null) {
                java.nio.ByteBuffer resultBuffer = java.nio.ByteBuffer.wrap(resultBytes);
                // Deserialize result - for now, return all block entities as the binary protocol
                // doesn't return a specific list of entities to tick
                List<Long> resultList = new ArrayList<>();
                for (BlockEntityData block : blockEntities) {
                    resultList.add(block.id());
                }
                totalBlocksProcessed += resultList.size();
                return resultList;
            }
        } catch (Exception binaryEx) {
            KneafCore.LOGGER.debug(BINARY_FALLBACK_MESSAGE, binaryEx.getMessage());
        }
        return new ArrayList<>();
    }

    private static List<Long> getBlockEntitiesToTickJson(List<BlockEntityData> blockEntities) {
        Map<String, Object> input = new HashMap<>();
        input.put(TICK_COUNT_KEY, tickCount++);
        input.put("block_entities", blockEntities);
        String jsonInput = gson.toJson(input);
        String jsonResult = null;
        if (jsonResult != null) {
            JsonObject result = gson.fromJson(jsonResult, JsonObject.class);
            JsonArray entitiesToTick = result.getAsJsonArray("block_entities_to_tick");
            List<Long> resultList = new ArrayList<>();
            for (JsonElement e : entitiesToTick) {
                resultList.add(e.getAsLong());
            }
            totalBlocksProcessed += resultList.size();
            return resultList;
        }
        return new ArrayList<>();
    }

    public static class ItemUpdate {
        private long id;
        private int newCount;

        public ItemUpdate(long id, int newCount) {
            this.id = id;
            this.newCount = newCount;
        }

        public long getId() {
            return id;
        }

        public int getNewCount() {
            return newCount;
        }
    }

    public static class ItemProcessResult {
        private List<Long> itemsToRemove;
        private long mergedCount;
        private long despawnedCount;
        private List<ItemUpdate> itemUpdates;

        public ItemProcessResult(List<Long> itemsToRemove, long mergedCount, long despawnedCount, List<ItemUpdate> itemUpdates) {
            this.itemsToRemove = itemsToRemove;
            this.mergedCount = mergedCount;
            this.despawnedCount = despawnedCount;
            this.itemUpdates = itemUpdates;
        }

        public List<Long> getItemsToRemove() {
            return itemsToRemove;
        }

        public long getMergedCount() {
            return mergedCount;
        }

        public long getDespawnedCount() {
            return despawnedCount;
        }

        public List<ItemUpdate> getItemUpdates() {
            return itemUpdates;
        }
    }

    public static class MobProcessResult {
        private List<Long> mobsToDisableAI;
        private List<Long> mobsToSimplifyAI;

        public MobProcessResult(List<Long> mobsToDisableAI, List<Long> mobsToSimplifyAI) {
            this.mobsToDisableAI = mobsToDisableAI;
            this.mobsToSimplifyAI = mobsToSimplifyAI;
        }
        public List<Long> getMobsToDisableAI() {
            return mobsToDisableAI;
        }

        public List<Long> getMobsToSimplifyAI() {
            return mobsToSimplifyAI;
        }
    }

    public static String getMemoryStats() {
        try {
            ensureNativeAvailable();
            return getMemoryStatsNative();
        } catch (Exception e) {
            KneafCore.LOGGER.error("Error getting memory stats from Rust: {}", e.getMessage(), e);
            return "{\"error\": \"Failed to get memory stats\"}";
        }
    }

    public static String getCpuStats() {
        try {
            ensureNativeAvailable();
            return getCpuStatsNative();
        } catch (Exception e) {
            KneafCore.LOGGER.error("Error getting CPU stats from Rust: {}", e.getMessage(), e);
            return "{\"error\": \"Failed to get CPU stats\"}";
        }
    }

    // Async Chunk Loading
    public static CompletableFuture<Integer> preGenerateNearbyChunksAsync(int centerX, int centerZ, int radius) {
        return asyncProcessor.supplyAsync(() -> preGenerateNearbyChunks(centerX, centerZ, radius), 30000, "preGenerateNearbyChunks");
    }

    public static void startValenceServer() {
        // Method removed - Valence integration is no longer supported
        KneafCore.LOGGER.info("Valence integration has been removed");
    }

    public static int preGenerateNearbyChunks(int centerX, int centerZ, int radius) {
        try {
            return preGenerateNearbyChunksNative(centerX, centerZ, radius);
        } catch (Exception e) {
            KneafCore.LOGGER.error("Error calling Rust for chunk generation: {}", e.getMessage(), e);
            return 0;
        }
    }

    public static boolean isChunkGenerated(int x, int z) {
        try {
            return isChunkGeneratedNative(x, z);
        } catch (Exception e) {
            KneafCore.LOGGER.error("Error calling Rust for chunk check: {}", e.getMessage(), e);
            return false;
        }
    }

    public static long getGeneratedChunkCount() {
        try {
            return getGeneratedChunkCountNative();
        } catch (Exception e) {
            KneafCore.LOGGER.error("Error calling Rust for chunk count: {}", e.getMessage(), e);
            return 0;
        }
    }

    public static double getCurrentTPS() { return currentTPS; }
    public static long getTotalEntitiesProcessed() { return totalEntitiesProcessed; }
    public static long getTotalMobsProcessed() { return totalMobsProcessed; }
    public static long getTotalBlocksProcessed() { return totalBlocksProcessed; }
    public static long getTotalMerged() { return totalMerged; }
    public static long getTotalDespawned() { return totalDespawned; }

    // --- Manual ByteBuffer serializers/deserializers (replaces com.kneaf.core.flatbuffers helpers) ---
    // Serializer/deserializer implementations moved to com.kneaf.core.binary.ManualSerializers
}