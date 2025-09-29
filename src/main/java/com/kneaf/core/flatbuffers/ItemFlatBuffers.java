package com.kneaf.core.flatbuffers;

import com.google.flatbuffers.FlatBufferBuilder;
import com.google.flatbuffers.Table;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * FlatBuffers serialization for ItemEntityData structures
 */
public class ItemFlatBuffers {
    
    private ItemFlatBuffers() {
        // Utility class
    }
    
    public static ByteBuffer serializeItemInput(long tickCount, java.util.List<com.kneaf.core.data.ItemEntityData> items) {
        // Estimate buffer size based on input list to minimize reallocations
        // Base size + estimated size per item + string overhead
        int estimatedSize = 1024 +
                           items.size() * 48 +   // ~48 bytes per item (id, 2 ints, 2 uints, string ref)
                           items.size() * 16;    // string overhead estimate
        FlatBufferBuilder builder = new FlatBufferBuilder(estimatedSize);
        
        // Serialize items
        int[] itemOffsets = new int[items.size()];
        for (int i = 0; i < items.size(); i++) {
            com.kneaf.core.data.ItemEntityData item = items.get(i);
            int itemTypeOffset = builder.createString(item.itemType());
            itemOffsets[i] = createItemEntityData(builder, item.id(), item.chunkX(), item.chunkZ(),
                                                itemTypeOffset, item.count(), item.ageSeconds());
        }
        int itemsOffset = builder.createVectorOfTables(itemOffsets);
        
        // Create item config
        int configOffset = createItemConfig(builder, 6000, 20, 0.98f, 0.98f);
        
        // Create ItemInput
        startItemInput(builder);
        addTickCount(builder, tickCount);
        addItems(builder, itemsOffset);
        addItemConfig(builder, configOffset);
        int inputOffset = endItemInput(builder);
        
        builder.finish(inputOffset);
        return builder.dataBuffer();
    }
    
    public static java.util.List<com.kneaf.core.data.ItemEntityData> deserializeItemProcessResult(ByteBuffer buffer) {
        buffer.rewind();
        ItemProcessResult result = ItemProcessResult.getRootAsItemProcessResult(buffer);
        
        java.util.List<com.kneaf.core.data.ItemEntityData> updatedItems = new java.util.ArrayList<>();
        for (int i = 0; i < result.updatedItemsLength(); i++) {
            ItemProcessResult.ItemUpdate itemUpdate = result.updatedItems(i);
            com.kneaf.core.data.ItemEntityData itemData = new com.kneaf.core.data.ItemEntityData(
                itemUpdate.id(),
                itemUpdate.chunkX(), itemUpdate.chunkZ(),
                "", // itemType will be set from original
                itemUpdate.count(), itemUpdate.ageSeconds()
            );
            updatedItems.add(itemData);
        }
        return updatedItems;
    }
    
    // ItemEntityData methods
    public static int createItemEntityData(FlatBufferBuilder builder, long id, int chunkX, int chunkZ,
                                         int itemType, int count, int ageSeconds) {
        startItemEntityData(builder);
        addItemId(builder, id);
        addChunkX(builder, chunkX);
        addChunkZ(builder, chunkZ);
        addItemType(builder, itemType);
        addCount(builder, count);
        addAgeSeconds(builder, ageSeconds);
        return endItemEntityData(builder);
    }
    
    public static void startItemEntityData(FlatBufferBuilder builder) { builder.startTable(5); }
    public static void addItemId(FlatBufferBuilder builder, long id) { builder.addLong(0, id, 0); }
    public static void addChunkX(FlatBufferBuilder builder, int chunkX) { builder.addInt(1, chunkX, 0); }
    public static void addChunkZ(FlatBufferBuilder builder, int chunkZ) { builder.addInt(2, chunkZ, 0); }
    public static void addItemType(FlatBufferBuilder builder, int itemType) { builder.addOffset(3, itemType, 0); }
    public static void addCount(FlatBufferBuilder builder, int count) { builder.addInt(4, count, 0); }
    public static void addAgeSeconds(FlatBufferBuilder builder, int ageSeconds) { builder.addInt(5, ageSeconds, 0); }
    public static int endItemEntityData(FlatBufferBuilder builder) { return builder.endTable(); }
    
    // ItemConfig methods
    public static int createItemConfig(FlatBufferBuilder builder, int despawnAge, int maxPickupDelay, float gravity, float friction) {
        startItemConfig(builder);
        addDespawnAge(builder, despawnAge);
        addMaxPickupDelay(builder, maxPickupDelay);
        addGravity(builder, gravity);
        addFriction(builder, friction);
        return endItemConfig(builder);
    }
    
    public static void startItemConfig(FlatBufferBuilder builder) { builder.startTable(4); }
    public static void addDespawnAge(FlatBufferBuilder builder, int despawnAge) { builder.addInt(0, despawnAge, 0); }
    public static void addMaxPickupDelay(FlatBufferBuilder builder, int maxPickupDelay) { builder.addInt(1, maxPickupDelay, 0); }
    public static void addGravity(FlatBufferBuilder builder, float gravity) { builder.addFloat(2, gravity, 0); }
    public static void addFriction(FlatBufferBuilder builder, float friction) { builder.addFloat(3, friction, 0); }
    public static int endItemConfig(FlatBufferBuilder builder) { return builder.endTable(); }
    
    // ItemInput methods
    public static void startItemInput(FlatBufferBuilder builder) { builder.startTable(3); }
    public static void addTickCount(FlatBufferBuilder builder, long tickCount) { builder.addLong(0, tickCount, 0); }
    public static void addItems(FlatBufferBuilder builder, int items) { builder.addOffset(1, items, 0); }
    public static void addItemConfig(FlatBufferBuilder builder, int itemConfig) { builder.addOffset(2, itemConfig, 0); }
    public static int endItemInput(FlatBufferBuilder builder) { return builder.endTable(); }
    
    // ItemUpdate methods
    public static int createItemUpdate(FlatBufferBuilder builder, long id, int chunkX, int chunkZ,
                                     int count, int ageSeconds) {
        startItemUpdate(builder);
        addUpdateId(builder, id);
        addUpdateChunkX(builder, chunkX);
        addUpdateChunkZ(builder, chunkZ);
        addUpdateCount(builder, count);
        addUpdateAgeSeconds(builder, ageSeconds);
        return endItemUpdate(builder);
    }
    
    public static void startItemUpdate(FlatBufferBuilder builder) { builder.startTable(5); }
    public static void addUpdateId(FlatBufferBuilder builder, long id) { builder.addLong(0, id, 0); }
    public static void addUpdateChunkX(FlatBufferBuilder builder, int chunkX) { builder.addInt(1, chunkX, 0); }
    public static void addUpdateChunkZ(FlatBufferBuilder builder, int chunkZ) { builder.addInt(2, chunkZ, 0); }
    public static void addUpdateCount(FlatBufferBuilder builder, int count) { builder.addInt(3, count, 0); }
    public static void addUpdateAgeSeconds(FlatBufferBuilder builder, int ageSeconds) { builder.addInt(4, ageSeconds, 0); }
    public static int endItemUpdate(FlatBufferBuilder builder) { return builder.endTable(); }
    
    // ItemProcessResult methods
    public static class ItemProcessResult extends Table {
        public static ItemProcessResult getRootAsItemProcessResult(ByteBuffer buffer) { return getRootAsItemProcessResult(buffer, new ItemProcessResult()); }
        public static ItemProcessResult getRootAsItemProcessResult(ByteBuffer buffer, ItemProcessResult obj) { buffer.order(ByteOrder.LITTLE_ENDIAN); return (obj.assign(buffer.getInt(buffer.position()) + buffer.position(), buffer)); }
        public void init(int index, ByteBuffer buffer) { __reset(index, buffer); }
        public ItemProcessResult assign(int index, ByteBuffer buffer) { init(index, buffer); return this; }
        
        public ItemUpdate updatedItems(int j) { return updatedItems(new ItemUpdate(), j); }
        public ItemUpdate updatedItems(ItemUpdate obj, int j) { int o = __offset(4); return o != 0 ? obj.assign(__indirect(__vector(o) + j * 4), bb) : null; }
        public int updatedItemsLength() { int o = __offset(4); return o != 0 ? __vector_len(o) : 0; }
        
        public static class ItemUpdate extends Table {
            public ItemUpdate() {
                // Empty constructor for FlatBuffers
            }
            public ItemUpdate assign(int index, ByteBuffer buffer) { init(index, buffer); return this; }
            public void init(int index, ByteBuffer buffer) { __reset(index, buffer); }
            public long id() { int o = __offset(4); return o != 0 ? bb.getLong(o + bb_pos) : 0; }
            public int chunkX() { int o = __offset(6); return o != 0 ? bb.getInt(o + bb_pos) : 0; }
            public int chunkZ() { int o = __offset(8); return o != 0 ? bb.getInt(o + bb_pos) : 0; }
            public int count() { int o = __offset(10); return o != 0 ? bb.getInt(o + bb_pos) : 0; }
            public int ageSeconds() { int o = __offset(12); return o != 0 ? bb.getInt(o + bb_pos) : 0; }
        }
    }
}