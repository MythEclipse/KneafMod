package com.kneaf.core.flatbuffers;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Manual binary serialization for MobData structures using ByteBuffer.
 */
public class MobFlatBuffers {
    private MobFlatBuffers() {}

    // Binary layout (little-endian):
    // [tickCount:long][numMobs:int][mobs...][aiConfig:4 floats]
    // Mob layout: [id:long][distance:float][isPassive:byte][etypeLen:int][etypeBytes]

    public static ByteBuffer serializeMobInput(long tickCount, List<com.kneaf.core.data.MobData> mobs) {
        int size = 8 + 4 + 16; // tick + num + config
        for (com.kneaf.core.data.MobData m : mobs) {
            byte[] et = m.entityType() != null ? m.entityType().getBytes(StandardCharsets.UTF_8) : new byte[0];
            size += 8 + 4 + 1 + 4 + et.length;
        }
        ByteBuffer buf = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        buf.putLong(tickCount);
        buf.putInt(mobs.size());
        for (com.kneaf.core.data.MobData m : mobs) {
            buf.putLong(m.id());
            buf.putFloat((float) m.distance());
            buf.put((byte) (m.isPassive() ? 1 : 0));
            byte[] et = m.entityType() != null ? m.entityType().getBytes(StandardCharsets.UTF_8) : new byte[0];
            buf.putInt(et.length);
            if (et.length > 0) buf.put(et);
        }
        // ai config defaults
        buf.putFloat(16.0f);
        buf.putFloat(32.0f);
        buf.putFloat(1.0f);
        buf.putFloat(0.5f);
        buf.flip();
        return buf;
    }

    public static List<com.kneaf.core.data.MobData> deserializeMobProcessResult(ByteBuffer buffer) {
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        buffer.rewind();
        int num = buffer.getInt();
        List<com.kneaf.core.data.MobData> out = new ArrayList<>();
        for (int i = 0; i < num; i++) {
            long id = buffer.getLong();
            float distance = buffer.getFloat();
            boolean isPassive = buffer.get() != 0;
            int etLen = buffer.getInt();
            String etype = "";
            if (etLen > 0) {
                byte[] etb = new byte[etLen];
                buffer.get(etb);
                etype = new String(etb, StandardCharsets.UTF_8);
            }
            out.add(new com.kneaf.core.data.MobData(id, distance, isPassive, etype));
        }
        return out;
    }
}