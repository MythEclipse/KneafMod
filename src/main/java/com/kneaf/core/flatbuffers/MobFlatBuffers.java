package com.kneaf.core.flatbuffers;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Refactored binary serialization for MobData structures using generic BinarySerializer.
 * Maintains same functionality and interface as original.
 */
public class MobFlatBuffers {
    private MobFlatBuffers() {}

    // Field descriptors for MobData serialization
    private static final List<BinarySerializer.FieldDescriptor<com.kneaf.core.data.MobData>> MOB_FIELD_DESCRIPTORS = List.of(
        BinarySerializer.FieldDescriptor.longField(com.kneaf.core.data.MobData::id),
        BinarySerializer.FieldDescriptor.floatField(m -> (float) m.distance()),
        BinarySerializer.FieldDescriptor.byteField(m -> (byte) (m.isPassive() ? 1 : 0)),
        BinarySerializer.FieldDescriptor.utf8StringField(com.kneaf.core.data.MobData::entityType)
    );

    // Config values for mob serialization
    private static final List<Float> MOB_CONFIG_FLOATS = List.of(16.0f, 32.0f, 1.0f, 0.5f);

    // Base size: tickCount(8) + numMobs(4) + config(4 floats = 16)
    private static final int MOB_BASE_SIZE = 8 + 4 + 16;

    /**
     * Serializes mob input data using generic BinarySerializer.
     * Maintains same binary format as original.
     */
    public static ByteBuffer serializeMobInput(long tickCount, List<com.kneaf.core.data.MobData> mobs) {
        BinarySerializer.SerializationConfig<com.kneaf.core.data.MobData> config =
            new BinarySerializer.SerializationConfig<>(MOB_BASE_SIZE, MOB_FIELD_DESCRIPTORS, MOB_CONFIG_FLOATS, null);
        
        return BinarySerializer.serializeList(tickCount, mobs, config, null);
    }

    /**
     * Deserializes mob process result using generic BinarySerializer.
     * Maintains same binary format as original.
     */
    public static List<com.kneaf.core.data.MobData> deserializeMobProcessResult(ByteBuffer buffer) {
        return BinarySerializer.deserializeList(buffer,
            fieldValues -> {
                // fieldValues order: id, distance, isPassive, entityType
                long id = (Long) fieldValues[0];
                float distance = (Float) fieldValues[1];
                byte isPassiveByte = (Byte) fieldValues[2];
                boolean isPassive = isPassiveByte != 0;
                String entityType = (String) fieldValues[3];
                return new com.kneaf.core.data.MobData(id, distance, isPassive, entityType);
            },
            MOB_FIELD_DESCRIPTORS);
    }
}