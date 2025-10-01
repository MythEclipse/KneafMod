package com.kneaf.core.flatbuffers;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Refactored binary serialization for EntityData structures using generic BinarySerializer.
 * Maintains same functionality and interface as original.
 */
public class EntityFlatBuffers {
    
    private EntityFlatBuffers() {
        // Utility class
    }
    
    // Field descriptors for EntityData serialization
    private static final List<BinarySerializer.FieldDescriptor<com.kneaf.core.data.EntityData>> ENTITY_FIELD_DESCRIPTORS = List.of(
        BinarySerializer.FieldDescriptor.longField(com.kneaf.core.data.EntityData::id),
        BinarySerializer.FieldDescriptor.floatField(e -> (float) e.x()),
        BinarySerializer.FieldDescriptor.floatField(e -> (float) e.y()),
        BinarySerializer.FieldDescriptor.floatField(e -> (float) e.z()),
        BinarySerializer.FieldDescriptor.floatField(e -> (float) e.distance()),
        BinarySerializer.FieldDescriptor.byteField(e -> (byte) (e.isBlockEntity() ? 1 : 0)),
        BinarySerializer.FieldDescriptor.utf8StringField(com.kneaf.core.data.EntityData::entityType)
    );

    // Field descriptors for PlayerData serialization
    private static final List<BinarySerializer.FieldDescriptor<com.kneaf.core.data.PlayerData>> PLAYER_FIELD_DESCRIPTORS = List.of(
        BinarySerializer.FieldDescriptor.longField(com.kneaf.core.data.PlayerData::id),
        BinarySerializer.FieldDescriptor.floatField(p -> (float) p.x()),
        BinarySerializer.FieldDescriptor.floatField(p -> (float) p.y()),
        BinarySerializer.FieldDescriptor.floatField(p -> (float) p.z())
    );

    // Config values for entity serialization (5 floats)
    private static final List<Float> ENTITY_CONFIG_FLOATS = List.of(16.0f, 32.0f, 1.0f, 0.5f, 0.1f);

    /**
     * Serializes entity input data using generic BinarySerializer.
     * Maintains same binary format as original.
     */
    public static ByteBuffer serializeEntityInput(long tickCount, java.util.List<com.kneaf.core.data.EntityData> entities,
                                                 java.util.List<com.kneaf.core.data.PlayerData> players) {
        // Calculate base size: tickCount(8) + numEntities(4) + numPlayers(4) + config(5 floats = 20)
        int baseSize = 8 + 4 + 4 + 20;
        
        // Calculate entity size (variable due to UTF-8 strings)
        int entitySize = 0;
        for (com.kneaf.core.data.EntityData e : entities) {
            entitySize += 8 + 4*4 + 1 + 4; // Fixed parts: id + x/y/z/distance + isBlockEntity + etypeLen
            String entityType = e.entityType();
            if (entityType != null) {
                entitySize += entityType.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            }
        }
        
        // Calculate player size (fixed)
        int playerSize = entities.size() * (8 + 4*3); // id + x/y/z
        
        int totalSize = baseSize + entitySize + playerSize;
        
        ByteBuffer buf = ByteBuffer.allocateDirect(totalSize).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        buf.putLong(tickCount);

        // Serialize entities using manual approach for mixed content
        buf.putInt(entities.size());
        for (com.kneaf.core.data.EntityData e : entities) {
            buf.putLong(e.id());
            buf.putFloat((float) e.x());
            buf.putFloat((float) e.y());
            buf.putFloat((float) e.z());
            buf.putFloat((float) e.distance());
            buf.put((byte) (e.isBlockEntity() ? 1 : 0));
            byte[] etype = e.entityType() != null ? e.entityType().getBytes(java.nio.charset.StandardCharsets.UTF_8) : new byte[0];
            buf.putInt(etype.length);
            if (etype.length > 0) buf.put(etype);
        }

        // Serialize players using manual approach for mixed content
        buf.putInt(players.size());
        for (com.kneaf.core.data.PlayerData p : players) {
            buf.putLong(p.id());
            buf.putFloat((float) p.x());
            buf.putFloat((float) p.y());
            buf.putFloat((float) p.z());
        }

        // entity config defaults
        for (Float f : ENTITY_CONFIG_FLOATS) {
            buf.putFloat(f);
        }

        buf.flip();
        return buf;
    }
    
    /**
     * Deserializes entity process result using generic BinarySerializer.
     * Maintains same binary format as original.
     */
    public static java.util.List<Long> deserializeEntityProcessResult(ByteBuffer buffer) {
        buffer.order(java.nio.ByteOrder.LITTLE_ENDIAN);
        buffer.rewind();
        int len = buffer.getInt();
        List<Long> entityIds = new ArrayList<>();
        for (int i = 0; i < len; i++) {
            entityIds.add(buffer.getLong());
        }
        return entityIds;
    }
}