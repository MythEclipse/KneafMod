package com.kneaf.core.flatbuffers;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Refactored binary serialization for ItemEntityData structures using generic BinarySerializer.
 * Maintains same functionality and interface as original.
 */
public class ItemFlatBuffers {
    private ItemFlatBuffers() {}

    // Field descriptors for ItemEntityData serialization
    private static final List<BinarySerializer.FieldDescriptor<com.kneaf.core.data.ItemEntityData>> ITEM_FIELD_DESCRIPTORS = List.of(
        BinarySerializer.FieldDescriptor.longField(com.kneaf.core.data.ItemEntityData::id),
        BinarySerializer.FieldDescriptor.intField(com.kneaf.core.data.ItemEntityData::chunkX),
        BinarySerializer.FieldDescriptor.intField(com.kneaf.core.data.ItemEntityData::chunkZ),
        BinarySerializer.FieldDescriptor.utf8StringField(com.kneaf.core.data.ItemEntityData::itemType),
        BinarySerializer.FieldDescriptor.intField(com.kneaf.core.data.ItemEntityData::count),
        BinarySerializer.FieldDescriptor.intField(com.kneaf.core.data.ItemEntityData::ageSeconds)
    );

    // Config values for item serialization
    private static final List<Integer> ITEM_CONFIG_INTS = List.of(6000, 20);
    private static final List<Float> ITEM_CONFIG_FLOATS = List.of(0.98f, 0.98f);

    // Base size: tickCount(8) + numItems(4) + config(2 ints + 2 floats = 16)
    private static final int ITEM_BASE_SIZE = 8 + 4 + 16;

    /**
     * Serializes item input data using generic BinarySerializer.
     * Maintains same binary format as original.
     */
    public static ByteBuffer serializeItemInput(long tickCount, List<com.kneaf.core.data.ItemEntityData> items) {
        BinarySerializer.SerializationConfig<com.kneaf.core.data.ItemEntityData> config =
            new BinarySerializer.SerializationConfig<>(ITEM_BASE_SIZE, ITEM_FIELD_DESCRIPTORS, ITEM_CONFIG_FLOATS, ITEM_CONFIG_INTS);
        
        return BinarySerializer.serializeList(tickCount, items, config, null);
    }

    /**
     * Deserializes item process result using generic BinarySerializer.
     * Maintains same binary format as original.
     */
    public static List<com.kneaf.core.data.ItemEntityData> deserializeItemProcessResult(ByteBuffer buffer) {
        return BinarySerializer.deserializeList(buffer,
            fieldValues -> {
                // fieldValues order: id, chunkX, chunkZ, itemType, count, ageSeconds
                long id = (Long) fieldValues[0];
                int chunkX = (Integer) fieldValues[1];
                int chunkZ = (Integer) fieldValues[2];
                String itemType = (String) fieldValues[3];
                int count = (Integer) fieldValues[4];
                int ageSeconds = (Integer) fieldValues[5];
                return new com.kneaf.core.data.ItemEntityData(id, chunkX, chunkZ, itemType, count, ageSeconds);
            },
            ITEM_FIELD_DESCRIPTORS);
    }
}