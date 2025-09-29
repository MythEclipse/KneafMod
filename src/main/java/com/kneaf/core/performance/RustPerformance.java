package com.kneaf.core.performance;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;
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
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.ConcurrentLinkedQueue;

public class RustPerformance {
    private RustPerformance() {}

    // Async processing executor
    private static final ExecutorService asyncExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r);
        t.setName("Kneaf-Async-Processor");
        t.setDaemon(true);
        return t;
    });

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
            // Attempt to load library normally; fallback handled elsewhere
            System.loadLibrary("rustperf");
            nativeAvailable = true;
        } catch (UnsatisfiedLinkError e) {
            KneafCore.LOGGER.info("rustperf native library not loaded via System.loadLibrary: {}", e.getMessage());
            nativeAvailable = false;
        }
    }

    static {
        KneafCore.LOGGER.info("Initializing RustPerformance native library");
        try {
            // Extract the native library from the JAR and load it
            String libName = "rustperf.dll";
            String resourcePath = "natives/" + libName;
            KneafCore.LOGGER.info("Loading native library from resource path: {}", resourcePath);
            InputStream in = RustPerformance.class.getClassLoader().getResourceAsStream(resourcePath);
            if (in == null) {
                KneafCore.LOGGER.warn("Native library not found in resources: {}. Native optimizations disabled.", resourcePath);
                nativeAvailable = false;
            } else {
                KneafCore.LOGGER.info("Found native library resource, extracting to temp directory");
                Path tempDir = Files.createTempDirectory("kneafcore-natives");
                Path tempFile = tempDir.resolve(libName);
                Files.copy(in, tempFile, StandardCopyOption.REPLACE_EXISTING);
                in.close();
                KneafCore.LOGGER.info("Extracted native library to: {}", tempFile.toAbsolutePath());
                tryLoadNative(tempFile);
                tempFile.toFile().deleteOnExit();
                tempDir.toFile().deleteOnExit();
            }
        } catch (Exception e) {
            KneafCore.LOGGER.warn("Failed to initialize Rust native library: {}. Native optimizations disabled.", e.getMessage());
            nativeAvailable = false;
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

    // Native methods - JSON (legacy)
    private static native String processEntitiesNative(String jsonInput);
    private static native String processItemEntitiesNative(String jsonInput);
    private static native String processMobAiNative(String jsonInput);
    private static native String processBlockEntitiesNative(String jsonInput);
    
    // Native methods - Binary FlatBuffers (new)
    private static native java.nio.ByteBuffer processEntitiesBinaryNative(java.nio.ByteBuffer input);
    private static native java.nio.ByteBuffer processItemEntitiesBinaryNative(java.nio.ByteBuffer input);
    private static native java.nio.ByteBuffer processMobAiBinaryNative(java.nio.ByteBuffer input);
    private static native java.nio.ByteBuffer processBlockEntitiesBinaryNative(java.nio.ByteBuffer input);
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

    public static List<Long> getEntitiesToTick(List<EntityData> entities, List<PlayerData> players) {
        try {
            // Use binary protocol if available, fallback to JSON
            if (nativeAvailable) {
                try {
                    // Serialize to FlatBuffers binary format
                    java.nio.ByteBuffer inputBuffer = com.kneaf.core.flatbuffers.EntityFlatBuffers.serializeEntityInput(
                        tickCount++, entities, players);
                    
                    // Call binary native method
                    java.nio.ByteBuffer resultBuffer = processEntitiesBinaryNative(inputBuffer);
                    
                    if (resultBuffer != null) {
                        // Deserialize result
                        List<Long> resultList = com.kneaf.core.flatbuffers.EntityFlatBuffers.deserializeEntityProcessResult(resultBuffer);
                        totalEntitiesProcessed += resultList.size();
                        return resultList;
                    }
                } catch (Exception binaryEx) {
                    KneafCore.LOGGER.debug("Binary protocol failed, falling back to JSON: {}", binaryEx.getMessage());
                    // Fall through to JSON fallback
                }
            }
            
            // JSON fallback
            Map<String, Object> input = new HashMap<>();
            input.put(TICK_COUNT_KEY, tickCount++);
            input.put("entities", entities);
            input.put("players", players);
            
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
            input.put("entityConfig", config);
            
            String jsonInput = gson.toJson(input);
            String jsonResult = processEntitiesNative(jsonInput);
            if (jsonResult != null) {
                JsonObject result = gson.fromJson(jsonResult, JsonObject.class);
                JsonArray entitiesToTick = result.getAsJsonArray("entitiesToTick");
                List<Long> resultList = new ArrayList<>();
                for (JsonElement e : entitiesToTick) {
                    resultList.add(e.getAsLong());
                }
                totalEntitiesProcessed += resultList.size();
                return resultList;
            } else {
                KneafCore.LOGGER.warn("Rust processing returned null, returning empty list");
                return new ArrayList<>();
            }
        } catch (Exception e) {
            KneafCore.LOGGER.error("Error calling Rust for entity processing: {}", e.getMessage(), e);
        }
        // Fallback: return all
        List<Long> all = new ArrayList<>();
        for (EntityData e : entities) {
            all.add(e.id());
        }
        return all;
    }

    public static ItemProcessResult processItemEntities(List<ItemEntityData> items) {
        try {
            // Use binary protocol if available, fallback to JSON
            if (nativeAvailable) {
                try {
                    // Serialize to FlatBuffers binary format
                    java.nio.ByteBuffer inputBuffer = com.kneaf.core.flatbuffers.ItemFlatBuffers.serializeItemInput(
                        tickCount, items);
                    
                    // Call binary native method
                    java.nio.ByteBuffer resultBuffer = processItemEntitiesBinaryNative(inputBuffer);
                    
                    if (resultBuffer != null) {
                        // Deserialize result
                        List<com.kneaf.core.data.ItemEntityData> updatedItems =
                            com.kneaf.core.flatbuffers.ItemFlatBuffers.deserializeItemProcessResult(resultBuffer);
                        
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
                    KneafCore.LOGGER.debug("Binary protocol failed, falling back to JSON: {}", binaryEx.getMessage());
                    // Fall through to JSON fallback
                }
            }
            
            // JSON fallback
            Map<String, Object> input = new HashMap<>();
            input.put("items", items);
            String jsonInput = gson.toJson(input);
            String jsonResult = processItemEntitiesNative(jsonInput);
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
                try {
                    // Serialize to FlatBuffers binary format
                    java.nio.ByteBuffer inputBuffer = com.kneaf.core.flatbuffers.MobFlatBuffers.serializeMobInput(
                        tickCount, mobs);
                    
                    // Call binary native method
                    java.nio.ByteBuffer resultBuffer = processMobAiBinaryNative(inputBuffer);
                    
                    if (resultBuffer != null) {
                        // Deserialize result
                        List<com.kneaf.core.data.MobData> updatedMobs =
                            com.kneaf.core.flatbuffers.MobFlatBuffers.deserializeMobProcessResult(resultBuffer);
                        
                        // For now, assume all returned mobs need AI simplification
                        List<Long> simplifyList = new ArrayList<>();
                        for (com.kneaf.core.data.MobData mob : updatedMobs) {
                            simplifyList.add(mob.id());
                        }
                        
                        totalMobsProcessed += mobs.size();
                        return new MobProcessResult(new ArrayList<>(), simplifyList);
                    }
                } catch (Exception binaryEx) {
                    KneafCore.LOGGER.debug("Binary protocol failed, falling back to JSON: {}", binaryEx.getMessage());
                    // Fall through to JSON fallback
                }
            }
            
            // JSON fallback
            Map<String, Object> input = new HashMap<>();
            input.put(TICK_COUNT_KEY, tickCount);
            input.put("mobs", mobs);
            String jsonInput = gson.toJson(input);
            String jsonResult = processMobAiNative(jsonInput);
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
        } catch (Exception e) {
            KneafCore.LOGGER.error("Error calling Rust for mob AI processing: {}", e.getMessage(), e);
        }
        // Fallback: no optimization
        return new MobProcessResult(new ArrayList<>(), new ArrayList<>());
    }

    public static List<Long> getBlockEntitiesToTick(List<BlockEntityData> blockEntities) {
        try {
            // Use binary protocol if available, fallback to JSON
            if (nativeAvailable) {
                try {
                    // Serialize to FlatBuffers binary format
                    java.nio.ByteBuffer inputBuffer = com.kneaf.core.flatbuffers.BlockFlatBuffers.serializeBlockInput(
                        tickCount++, blockEntities);
                    
                    // Call binary native method
                    java.nio.ByteBuffer resultBuffer = processBlockEntitiesBinaryNative(inputBuffer);
                    
                    if (resultBuffer != null) {
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
                    KneafCore.LOGGER.debug("Binary protocol failed, falling back to JSON: {}", binaryEx.getMessage());
                    // Fall through to JSON fallback
                }
            }
            
            // JSON fallback
            Map<String, Object> input = new HashMap<>();
            input.put(TICK_COUNT_KEY, tickCount++);
            input.put("block_entities", blockEntities);
            String jsonInput = gson.toJson(input);
            String jsonResult = processBlockEntitiesNative(jsonInput);
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
        return CompletableFuture.supplyAsync(() -> preGenerateNearbyChunks(centerX, centerZ, radius), asyncExecutor);
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
}