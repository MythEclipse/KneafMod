package com.kneaf.core.flatbuffers;

import com.google.flatbuffers.FlatBufferBuilder;
import com.google.flatbuffers.Table;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * FlatBuffers serialization for EntityData structures
 */
public class EntityFlatBuffers {
    
    private EntityFlatBuffers() {
        // Utility class
    }
    
    public static ByteBuffer serializeEntityInput(long tickCount, java.util.List<com.kneaf.core.data.EntityData> entities,
                                                 java.util.List<com.kneaf.core.data.PlayerData> players) {
        // Estimate buffer size based on input lists to minimize reallocations
        // Base size + estimated size per entity/player + string overhead
        int estimatedSize = 1024 +
                           entities.size() * 64 +  // ~64 bytes per entity (id, 3 floats, bool, string ref)
                           players.size() * 32 +   // ~32 bytes per player (id, 3 floats)
                           (entities.size() + players.size()) * 16; // string overhead estimate
        FlatBufferBuilder builder = new FlatBufferBuilder(estimatedSize);
        
        // Serialize entities
        int[] entityOffsets = new int[entities.size()];
        for (int i = 0; i < entities.size(); i++) {
            com.kneaf.core.data.EntityData entity = entities.get(i);
            entityOffsets[i] = createEntityData(builder, entity);
        }
        int entitiesOffset = builder.createVectorOfTables(entityOffsets);
        
        // Serialize players
        int[] playerOffsets = new int[players.size()];
        for (int i = 0; i < players.size(); i++) {
            com.kneaf.core.data.PlayerData player = players.get(i);
            playerOffsets[i] = createPlayerData(builder, player.id(), (float) player.x(), (float) player.y(), (float) player.z());
        }
        int playersOffset = builder.createVectorOfTables(playerOffsets);
        
        // Create entity config
        int configOffset = createEntityConfig(builder, 16.0f, 32.0f, 1.0f, 0.5f, 0.1f);
        
        // Create EntityInput
        startEntityInput(builder);
        addTickCount(builder, tickCount);
        addEntities(builder, entitiesOffset);
        addPlayers(builder, playersOffset);
        addEntityConfig(builder, configOffset);
        int inputOffset = endEntityInput(builder);
        
        builder.finish(inputOffset);
        return builder.dataBuffer();
    }
    
    public static java.util.List<Long> deserializeEntityProcessResult(ByteBuffer buffer) {
        buffer.rewind();
        EntityProcessResult result = EntityProcessResult.getRootAsEntityProcessResult(buffer);
        
        java.util.List<Long> entityIds = new java.util.ArrayList<>();
        for (int i = 0; i < result.entitiesToTickLength(); i++) {
            entityIds.add(result.entitiesToTick(i));
        }
        return entityIds;
    }
    
    // EntityData methods
    public static int createEntityData(FlatBufferBuilder builder, com.kneaf.core.data.EntityData entity) {
        int entityTypeOffset = builder.createString(entity.entityType());
        startEntityData(builder);
        addId(builder, entity.id());
        addEntityType(builder, entityTypeOffset);
        addX(builder, (float) entity.x());
        addY(builder, (float) entity.y());
        addZ(builder, (float) entity.z());
        addDistance(builder, (float) entity.distance());
        addIsBlockEntity(builder, entity.isBlockEntity());
        return endEntityData(builder);
    }
    
    public static void startEntityData(FlatBufferBuilder builder) { builder.startTable(6); }
    public static void addId(FlatBufferBuilder builder, long id) { builder.addLong(0, id, 0); }
    public static void addEntityType(FlatBufferBuilder builder, int entityType) { builder.addOffset(1, entityType, 0); }
    public static void addX(FlatBufferBuilder builder, float x) { builder.addFloat(2, x, 0); }
    public static void addY(FlatBufferBuilder builder, float y) { builder.addFloat(3, y, 0); }
    public static void addZ(FlatBufferBuilder builder, float z) { builder.addFloat(4, z, 0); }
    public static void addDistance(FlatBufferBuilder builder, float distance) { builder.addFloat(5, distance, 0); }
    public static void addIsBlockEntity(FlatBufferBuilder builder, boolean isBlockEntity) { builder.addBoolean(6, isBlockEntity, false); }
    public static int endEntityData(FlatBufferBuilder builder) { return builder.endTable(); }
    
    // PlayerData methods
    public static int createPlayerData(FlatBufferBuilder builder, long id, float x, float y, float z) {
        startPlayerData(builder);
        addPlayerId(builder, id);
        addPlayerX(builder, x);
        addPlayerY(builder, y);
        addPlayerZ(builder, z);
        return endPlayerData(builder);
    }
    
    public static void startPlayerData(FlatBufferBuilder builder) { builder.startTable(4); }
    public static void addPlayerId(FlatBufferBuilder builder, long id) { builder.addLong(0, id, 0); }
    public static void addPlayerX(FlatBufferBuilder builder, float x) { builder.addFloat(1, x, 0); }
    public static void addPlayerY(FlatBufferBuilder builder, float y) { builder.addFloat(2, y, 0); }
    public static void addPlayerZ(FlatBufferBuilder builder, float z) { builder.addFloat(3, z, 0); }
    public static int endPlayerData(FlatBufferBuilder builder) { return builder.endTable(); }
    
    // EntityConfig methods
    public static int createEntityConfig(FlatBufferBuilder builder, float closeRadius, float mediumRadius, 
                                       float closeRate, float mediumRate, float farRate) {
        startEntityConfig(builder);
        addCloseRadius(builder, closeRadius);
        addMediumRadius(builder, mediumRadius);
        addCloseRate(builder, closeRate);
        addMediumRate(builder, mediumRate);
        addFarRate(builder, farRate);
        return endEntityConfig(builder);
    }
    
    public static void startEntityConfig(FlatBufferBuilder builder) { builder.startTable(5); }
    public static void addCloseRadius(FlatBufferBuilder builder, float closeRadius) { builder.addFloat(0, closeRadius, 0); }
    public static void addMediumRadius(FlatBufferBuilder builder, float mediumRadius) { builder.addFloat(1, mediumRadius, 0); }
    public static void addCloseRate(FlatBufferBuilder builder, float closeRate) { builder.addFloat(2, closeRate, 0); }
    public static void addMediumRate(FlatBufferBuilder builder, float mediumRate) { builder.addFloat(3, mediumRate, 0); }
    public static void addFarRate(FlatBufferBuilder builder, float farRate) { builder.addFloat(4, farRate, 0); }
    public static int endEntityConfig(FlatBufferBuilder builder) { return builder.endTable(); }
    
    // EntityInput methods
    public static void startEntityInput(FlatBufferBuilder builder) { builder.startTable(4); }
    public static void addTickCount(FlatBufferBuilder builder, long tickCount) { builder.addLong(0, tickCount, 0); }
    public static void addEntities(FlatBufferBuilder builder, int entities) { builder.addOffset(1, entities, 0); }
    public static void addPlayers(FlatBufferBuilder builder, int players) { builder.addOffset(2, players, 0); }
    public static void addEntityConfig(FlatBufferBuilder builder, int entityConfig) { builder.addOffset(3, entityConfig, 0); }
    public static int endEntityInput(FlatBufferBuilder builder) { return builder.endTable(); }
    
    // EntityProcessResult methods
    public static class EntityProcessResult extends Table {
        public static EntityProcessResult getRootAsEntityProcessResult(ByteBuffer buffer) { return getRootAsEntityProcessResult(buffer, new EntityProcessResult()); }
        public static EntityProcessResult getRootAsEntityProcessResult(ByteBuffer buffer, EntityProcessResult obj) { buffer.order(ByteOrder.LITTLE_ENDIAN); return (obj.assign(buffer.getInt(buffer.position()) + buffer.position(), buffer)); }
        public void init(int index, ByteBuffer buffer) { __reset(index, buffer); }
        public EntityProcessResult assign(int index, ByteBuffer buffer) { init(index, buffer); return this; }
        
        public long entitiesToTick(int j) { int o = __offset(4); return o != 0 ? bb.getLong(__vector(o) + j * 8) : 0; }
        public int entitiesToTickLength() { int o = __offset(4); return o != 0 ? __vector_len(o) : 0; }
        public ByteBuffer entitiesToTickAsByteBuffer() { return __vector_as_bytebuffer(4, 8); }
        public ByteBuffer entitiesToTickInByteBuffer(ByteBuffer buffer) { return __vector_in_bytebuffer(buffer, 4, 8); }
    }
}