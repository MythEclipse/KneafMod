package com.kneaf.core.flatbuffers;

import com.google.flatbuffers.FlatBufferBuilder;
import com.google.flatbuffers.Table;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * FlatBuffers serialization for MobData structures
 */
public class MobFlatBuffers {

    private MobFlatBuffers() {}

    public static ByteBuffer serializeMobInput(long tickCount, java.util.List<com.kneaf.core.data.MobData> mobs) {
        // Estimate buffer size based on input list to minimize reallocations
        // Base size + estimated size per mob + string overhead
        int estimatedSize = 1024 +
                           mobs.size() * 32 +    // ~32 bytes per mob (id, float, bool, string ref)
                           mobs.size() * 16;     // string overhead estimate
        FlatBufferBuilder builder = new FlatBufferBuilder(estimatedSize);
        
        // Serialize mobs
        int[] mobOffsets = new int[mobs.size()];
        for (int i = 0; i < mobs.size(); i++) {
            com.kneaf.core.data.MobData mob = mobs.get(i);
            int entityTypeOffset = builder.createString(mob.entityType());
            mobOffsets[i] = createMobData(builder, mob.id(), (float) mob.distance(), mob.isPassive(), entityTypeOffset);
        }
        int mobsOffset = builder.createVectorOfTables(mobOffsets);
        
        // Create AI config
        int configOffset = createAiConfig(builder, 16.0f, 32.0f, 1.0f, 0.5f);
        
        // Create MobInput
        startMobInput(builder);
        addTickCount(builder, tickCount);
        addMobs(builder, mobsOffset);
        addAiConfig(builder, configOffset);
        int inputOffset = endMobInput(builder);
        
        builder.finish(inputOffset);
        return builder.dataBuffer();
    }
    
    public static java.util.List<com.kneaf.core.data.MobData> deserializeMobProcessResult(ByteBuffer buffer) {
        buffer.rewind();
        MobProcessResult result = MobProcessResult.getRootAsMobProcessResult(buffer);
        
        java.util.List<com.kneaf.core.data.MobData> updatedMobs = new java.util.ArrayList<>();
        for (int i = 0; i < result.updatedMobsLength(); i++) {
            MobProcessResult.MobUpdate mobUpdate = result.updatedMobs(i);
            com.kneaf.core.data.MobData mobData = new com.kneaf.core.data.MobData(
                mobUpdate.id(),
                mobUpdate.distance(), mobUpdate.isPassive(),
                mobUpdate.entityType()
            );
            updatedMobs.add(mobData);
        }
        return updatedMobs;
    }
    
    // MobData methods
    public static int createMobData(FlatBufferBuilder builder, long id, float distance, boolean isPassive, int entityType) {
        startMobData(builder);
        addMobId(builder, id);
        addDistance(builder, distance);
        addIsPassive(builder, isPassive);
        addEntityType(builder, entityType);
        return endMobData(builder);
    }
    
    public static void startMobData(FlatBufferBuilder builder) { builder.startTable(4); }
    public static void addMobId(FlatBufferBuilder builder, long id) { builder.addLong(id); }
    public static void addDistance(FlatBufferBuilder builder, float distance) { builder.addFloat(distance); }
    public static void addIsPassive(FlatBufferBuilder builder, boolean isPassive) { builder.addBoolean(isPassive); }
    public static void addEntityType(FlatBufferBuilder builder, int entityType) { builder.addOffset(entityType); }
    public static int endMobData(FlatBufferBuilder builder) { return builder.endTable(); }
    
    // AiConfig methods
    public static int createAiConfig(FlatBufferBuilder builder, float detectionRange, float followRange, 
                                   float moveSpeed, float attackDamage) {
        startAiConfig(builder);
        addDetectionRange(builder, detectionRange);
        addFollowRange(builder, followRange);
        addMoveSpeed(builder, moveSpeed);
        addAttackDamage(builder, attackDamage);
        return endAiConfig(builder);
    }
    
    public static void startAiConfig(FlatBufferBuilder builder) { builder.startTable(4); }
    public static void addDetectionRange(FlatBufferBuilder builder, float detectionRange) { builder.addFloat(detectionRange); }
    public static void addFollowRange(FlatBufferBuilder builder, float followRange) { builder.addFloat(followRange); }
    public static void addMoveSpeed(FlatBufferBuilder builder, float moveSpeed) { builder.addFloat(moveSpeed); }
    public static void addAttackDamage(FlatBufferBuilder builder, float attackDamage) { builder.addFloat(attackDamage); }
    public static int endAiConfig(FlatBufferBuilder builder) { return builder.endTable(); }
    
    // MobInput methods
    public static void startMobInput(FlatBufferBuilder builder) { builder.startTable(3); }
    public static void addTickCount(FlatBufferBuilder builder, long tickCount) { builder.addLong(tickCount); }
    public static void addMobs(FlatBufferBuilder builder, int mobs) { builder.addOffset(mobs); }
    public static void addAiConfig(FlatBufferBuilder builder, int aiConfig) { builder.addOffset(aiConfig); }
    public static int endMobInput(FlatBufferBuilder builder) { return builder.endTable(); }
    
    // MobProcessResult methods
    public static class MobProcessResult extends Table {
        public static MobProcessResult getRootAsMobProcessResult(ByteBuffer buffer) { return getRootAsMobProcessResult(buffer, new MobProcessResult()); }
        public static MobProcessResult getRootAsMobProcessResult(ByteBuffer buffer, MobProcessResult obj) { buffer.order(ByteOrder.LITTLE_ENDIAN); return (obj.assign(buffer.getInt(buffer.position()) + buffer.position(), buffer)); }
        public void init(int index, ByteBuffer buffer) { __reset(index, buffer); }
        public MobProcessResult assign(int index, ByteBuffer buffer) { init(index, buffer); return this; }
        
        public MobUpdate updatedMobs(int j) { return updatedMobs(new MobUpdate(), j); }
        public MobUpdate updatedMobs(MobUpdate obj, int j) { int o = __offset(4); return o != 0 ? obj.assign(__indirect(__vector(o) + j * 4), bb) : null; }
        public int updatedMobsLength() { int o = __offset(4); return o != 0 ? __vector_len(o) : 0; }
        
        public static class MobUpdate extends Table {
            public MobUpdate() { // Default constructor for FlatBuffers
            }
            public MobUpdate assign(int index, ByteBuffer buffer) { init(index, buffer); return this; }
            public void init(int index, ByteBuffer buffer) { __reset(index, buffer); }
            public long id() { int o = __offset(4); return o != 0 ? bb.getLong(o + bb_pos) : 0; }
            public float distance() { int o = __offset(6); return o != 0 ? bb.getFloat(o + bb_pos) : 0; }
            public boolean isPassive() { int o = __offset(8); return o != 0 && bb.get(o + bb_pos) != 0; }
            public String entityType() { int o = __offset(10); return o != 0 ? __string(o + bb_pos) : null; }
        }
    }
}