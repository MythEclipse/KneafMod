package com.kneaf.core.flatbuffers;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * FlatBuffers serialization for EntityData structures
 */
public class EntityFlatBuffers {
    
    private EntityFlatBuffers() {
        // Utility class
    }
    
    public static ByteBuffer serializeEntityInput(long tickCount, java.util.List<com.kneaf.core.data.EntityData> entities,
                                                 java.util.List<com.kneaf.core.data.PlayerData> players) {
        // Manual binary format (little-endian):
        // [tickCount:long][numEntities:int][entities...][numPlayers:int][players...][config:5 floats]
        // Entity layout: [id:long][x:float][y:float][z:float][distance:float][isBlockEntity:byte][etypeLen:int][etypeBytes]
        // Player layout: [id:long][x:float][y:float][z:float]

        // compute exact size
        int size = 8 + 4 + 4 + 20; // tickCount + numEntities + numPlayers + config(5 floats)
        for (com.kneaf.core.data.EntityData e : entities) {
            byte[] etype = e.entityType() != null ? e.entityType().getBytes(StandardCharsets.UTF_8) : new byte[0];
            size += 8 + 4*4 + 1 + 4 + etype.length; // id + x/y/z/distance (4 floats) + isBlockEntity + etypeLen + bytes
        }
        for (com.kneaf.core.data.PlayerData p : players) {
            size += 8 + 4*3; // id + x/y/z floats
        }

        ByteBuffer buf = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        buf.putLong(tickCount);

        // entities
        buf.putInt(entities.size());
        for (com.kneaf.core.data.EntityData e : entities) {
            buf.putLong(e.id());
            buf.putFloat((float) e.x());
            buf.putFloat((float) e.y());
            buf.putFloat((float) e.z());
            buf.putFloat((float) e.distance());
            buf.put((byte) (e.isBlockEntity() ? 1 : 0));
            byte[] etype = e.entityType() != null ? e.entityType().getBytes(StandardCharsets.UTF_8) : new byte[0];
            buf.putInt(etype.length);
            if (etype.length > 0) buf.put(etype);
        }

        // players
        buf.putInt(players.size());
        for (com.kneaf.core.data.PlayerData p : players) {
            buf.putLong(p.id());
            buf.putFloat((float) p.x());
            buf.putFloat((float) p.y());
            buf.putFloat((float) p.z());
        }

        // entity config defaults (keep fields to match original shape)
        buf.putFloat(16.0f);
        buf.putFloat(32.0f);
        buf.putFloat(1.0f);
        buf.putFloat(0.5f);
        buf.putFloat(0.1f);

        buf.flip();
        return buf;
    }
    
    public static java.util.List<Long> deserializeEntityProcessResult(ByteBuffer buffer) {
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        buffer.rewind();
        int len = buffer.getInt();
        List<Long> entityIds = new ArrayList<>();
        for (int i = 0; i < len; i++) {
            entityIds.add(buffer.getLong());
        }
        return entityIds;
    }
    
    // Original FlatBuffers helper methods removed. Manual binary format used instead.
}