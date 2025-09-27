package com.kneaf.core;

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

public class RustPerformance {
    private static long tickCount = 0;
    // Metrics
    public static long lastTickNano = 0;
    public static double currentTPS = 20.0;
    public static long totalNormalTicks = 0;
    public static long totalThrottledTicks = 0;
    public static long totalMerged = 0;
    public static long totalDespawned = 0;
    private static final Gson gson = new Gson();

    static {
        ExampleMod.LOGGER.info("Initializing RustPerformance native library");
        try {
            // Extract the native library from the JAR and load it
            String libName = "rustperf.dll";
            String resourcePath = "natives/" + libName;
            ExampleMod.LOGGER.info("Loading native library from resource path: {}", resourcePath);
            InputStream in = RustPerformance.class.getClassLoader().getResourceAsStream(resourcePath);
            if (in == null) {
                ExampleMod.LOGGER.error("Native library not found: {}", resourcePath);
                throw new RuntimeException("Native library not found: " + resourcePath);
            }
            ExampleMod.LOGGER.info("Found native library resource, extracting to temp directory");
            Path tempDir = Files.createTempDirectory("kneafcore-natives");
            Path tempFile = tempDir.resolve(libName);
            Files.copy(in, tempFile, StandardCopyOption.REPLACE_EXISTING);
            in.close();
            ExampleMod.LOGGER.info("Extracted native library to: {}", tempFile.toAbsolutePath());
            System.load(tempFile.toAbsolutePath().toString());
            ExampleMod.LOGGER.info("Successfully loaded native library");
            // Optionally delete on exit, but for now keep it
            tempFile.toFile().deleteOnExit();
            tempDir.toFile().deleteOnExit();
        } catch (Exception e) {
            ExampleMod.LOGGER.error("Failed to load Rust library", e);
            throw new RuntimeException("Failed to load Rust library", e);
        }
    }

    // Native methods
    private static native String processEntitiesNative(String jsonInput);
    private static native String processItemEntitiesNative(String jsonInput);
    private static native String processMobAiNative(String jsonInput);
    private static native void freeStringNative(String s);

    public static List<Long> getEntitiesToTick(List<EntityData> entities) {
        try {
            Map<String, Object> input = new HashMap<>();
            input.put("tick_count", tickCount++);
            input.put("entities", entities);
            String jsonInput = gson.toJson(input);
            String jsonResult = processEntitiesNative(jsonInput);
            if (jsonResult != null) {
                JsonObject result = gson.fromJson(jsonResult, JsonObject.class);
                JsonArray entitiesToTick = result.getAsJsonArray("entities_to_tick");
                List<Long> resultList = new ArrayList<>();
                for (JsonElement e : entitiesToTick) {
                    resultList.add(e.getAsLong());
                }
                totalNormalTicks += resultList.size();
                totalThrottledTicks += entities.size() - resultList.size();
                return resultList;
            }
        } catch (Exception e) {
            ExampleMod.LOGGER.error("Error calling Rust for entity processing", e);
        }
        // Fallback: return all
        List<Long> all = new ArrayList<>();
        for (EntityData e : entities) {
            all.add(e.id);
        }
        return all;
    }

    public static ItemProcessResult processItemEntities(List<ItemEntityData> items) {
        try {
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
            ExampleMod.LOGGER.error("Error calling Rust for item processing", e);
        }
        // Fallback: no optimization
        return new ItemProcessResult(new ArrayList<>(), 0, 0, new ArrayList<>());
    }

    public static MobProcessResult processMobAI(List<MobData> mobs) {
        try {
            Map<String, Object> input = new HashMap<>();
            input.put("tick_count", tickCount);
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
                return new MobProcessResult(disableList, simplifyList);
            }
        } catch (Exception e) {
            ExampleMod.LOGGER.error("Error calling Rust for mob AI processing", e);
        }
        // Fallback: no optimization
        return new MobProcessResult(new ArrayList<>(), new ArrayList<>());
    }

    public static class ItemUpdate {
        public long id;
        public int newCount;

        public ItemUpdate(long id, int newCount) {
            this.id = id;
            this.newCount = newCount;
        }
    }

    public static class ItemProcessResult {
        public List<Long> itemsToRemove;
        public long mergedCount;
        public long despawnedCount;
        public List<ItemUpdate> itemUpdates;

        public ItemProcessResult(List<Long> itemsToRemove, long mergedCount, long despawnedCount, List<ItemUpdate> itemUpdates) {
            this.itemsToRemove = itemsToRemove;
            this.mergedCount = mergedCount;
            this.despawnedCount = despawnedCount;
            this.itemUpdates = itemUpdates;
        }
    }

    public static class MobProcessResult {
        public List<Long> mobsToDisableAI;
        public List<Long> mobsToSimplifyAI;

        public MobProcessResult(List<Long> mobsToDisableAI, List<Long> mobsToSimplifyAI) {
            this.mobsToDisableAI = mobsToDisableAI;
            this.mobsToSimplifyAI = mobsToSimplifyAI;
        }
    }

    public static double getCurrentTPS() { return currentTPS; }
    public static long getTotalNormalTicks() { return totalNormalTicks; }
    public static long getTotalThrottledTicks() { return totalThrottledTicks; }
    public static long getTotalMerged() { return totalMerged; }
    public static long getTotalDespawned() { return totalDespawned; }
}