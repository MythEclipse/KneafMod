package com.kneaf.core.flatbuffers;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Refactored binary serialization for BlockEntityData structures using generic BinarySerializer.
 * Maintains same functionality and interface as original.
 */
public class BlockFlatBuffers {
    private BlockFlatBuffers() {}

    // Field descriptors for BlockEntityData serialization
    private static final List<BinarySerializer.FieldDescriptor<com.kneaf.core.data.BlockEntityData>> BLOCK_FIELD_DESCRIPTORS = List.of(
        BinarySerializer.FieldDescriptor.longField(com.kneaf.core.data.BlockEntityData::id),
        BinarySerializer.FieldDescriptor.floatField(b -> (float) b.distance()),
        BinarySerializer.FieldDescriptor.utf8StringField(com.kneaf.core.data.BlockEntityData::blockType),
        BinarySerializer.FieldDescriptor.intField(com.kneaf.core.data.BlockEntityData::x),
        BinarySerializer.FieldDescriptor.intField(com.kneaf.core.data.BlockEntityData::y),
        BinarySerializer.FieldDescriptor.intField(com.kneaf.core.data.BlockEntityData::z)
    );

    // Config values for block serialization
    private static final List<Integer> BLOCK_CONFIG_INTS = List.of(8, 16); // maxRedstonePower, tickRadius
    private static final List<Float> BLOCK_CONFIG_FLOATS = List.of(0.1f, 0.05f); // tickChance, updateChance

    // Base size: tickCount(8) + numBlocks(4) + config(4 ints + 2 floats = 24)
    private static final int BLOCK_BASE_SIZE = 8 + 4 + 24;

    /**
     * Serializes block input data using generic BinarySerializer.
     * Maintains same binary format as original.
     */
    public static ByteBuffer serializeBlockInput(long tickCount, List<com.kneaf.core.data.BlockEntityData> blocks) {
        // Use the generic serializer with custom config handling
        BinarySerializer.SerializationConfig<com.kneaf.core.data.BlockEntityData> config =
            new BinarySerializer.SerializationConfig<>(BLOCK_BASE_SIZE, BLOCK_FIELD_DESCRIPTORS, BLOCK_CONFIG_FLOATS, BLOCK_CONFIG_INTS);
        
        return BinarySerializer.serializeList(tickCount, blocks, config, null);
    }

    /**
     * Deserializes block process result using generic BinarySerializer.
     * Maintains same binary format as original.
     */
    public static List<com.kneaf.core.data.BlockEntityData> deserializeBlockProcessResult(ByteBuffer buffer) {
        // Use the generic deserializer with proper field mapping
        return BinarySerializer.deserializeList(buffer,
            fieldValues -> {
                // fieldValues order: id, distance, blockType, x, y, z
                long id = (Long) fieldValues[0];
                float distance = (Float) fieldValues[1];
                String blockType = (String) fieldValues[2];
                int x = (Integer) fieldValues[3];
                int y = (Integer) fieldValues[4];
                int z = (Integer) fieldValues[5];
                return new com.kneaf.core.data.BlockEntityData(id, distance, blockType, x, y, z);
            },
            BLOCK_FIELD_DESCRIPTORS);
    }
}