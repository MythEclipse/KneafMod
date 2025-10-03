package com.kneaf.core.binary;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Manual binary (ByteBuffer) serializers/deserializers extracted from RustPerformance.
 * Keeps the same binary layout expectations as the previous inlined implementations.
 */
public final class ManualSerializers {
    private ManualSerializers() {}

    public static ByteBuffer serializeEntityInput(long tickCount, List<com.kneaf.core.data.EntityData> entities,
                                                   List<com.kneaf.core.data.PlayerData> players) {
        int baseSize = 8 + 4 + 4 + 20; // tick + numEntities + numPlayers + 5 config floats
        int entitySize = 0;
        for (com.kneaf.core.data.EntityData e : entities) {
            entitySize += 8 + 4*4 + 1 + 4; // id + x/y/z/distance + isBlockEntity + etypeLen
            String entityType = e.entityType();
            if (entityType != null) entitySize += entityType.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        }
        int playerSize = players.size() * (8 + 4*3);
        int totalSize = baseSize + entitySize + playerSize;
        ByteBuffer buf = ByteBuffer.allocateDirect(totalSize).order(ByteOrder.LITTLE_ENDIAN);
        buf.putLong(tickCount);
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
        buf.putInt(players.size());
        for (com.kneaf.core.data.PlayerData p : players) {
            buf.putLong(p.id());
            buf.putFloat((float) p.x());
            buf.putFloat((float) p.y());
            buf.putFloat((float) p.z());
        }
        float[] cfg = new float[]{16.0f, 32.0f, 1.0f, 0.5f, 0.1f};
        for (float f : cfg) buf.putFloat(f);
        buf.flip();
        return buf;
    }

    public static List<Long> deserializeEntityProcessResult(ByteBuffer buffer) {
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        buffer.rewind();
        // Be tolerant: accept either [num:i32][ids...] or [tick:u64][num:i32][ids...]
        int rem = buffer.remaining();
        if (rem < 4) return List.of();

        // Helper to safely read an int/long at absolute offsets without changing position
        java.nio.ByteBuffer dup = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN);

        // Try primary: [num:i32][ids...]
        int candidateNum = dup.getInt(0);
        if (candidateNum >= 0 && 4 + (long)candidateNum * 8 <= rem) {
            List<Long> ids = new ArrayList<>(Math.max(0, candidateNum));
            for (int i = 0; i < candidateNum; i++) {
                ids.add(dup.getLong(4 + i * 8));
            }
            return ids;
        }

        // Try tick-prefixed: [tick:u64][num:i32][ids...]
        if (rem >= 12) {
            int numItems = dup.getInt(8);
            if (numItems >= 0 && 12 + (long)numItems * 8 <= rem) {
                List<Long> ids = new ArrayList<>(Math.max(0, numItems));
                for (int i = 0; i < numItems; i++) {
                    ids.add(dup.getLong(12 + i * 8));
                }
                return ids;
            }
        }

        // No recognized layout
        return List.of();
    }

    public static ByteBuffer serializeItemInput(long tickCount, List<com.kneaf.core.data.ItemEntityData> items) {
        int baseSize = 8 + 4 + 16; // tick + num + config
        int payload = 0;
        for (com.kneaf.core.data.ItemEntityData it : items) {
            payload += 8 + 4 + 4 + 4 + 4 + 4; // id + chunkX + chunkZ + itemTypeLen + count + age
            String t = it.itemType(); if (t != null) payload += t.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        }
        ByteBuffer buf = ByteBuffer.allocateDirect(baseSize + payload).order(ByteOrder.LITTLE_ENDIAN);
        buf.putLong(tickCount);
        buf.putInt(items.size());
        for (com.kneaf.core.data.ItemEntityData it : items) {
            buf.putLong(it.id());
            buf.putInt(it.chunkX());
            buf.putInt(it.chunkZ());
            byte[] name = it.itemType() != null ? it.itemType().getBytes(java.nio.charset.StandardCharsets.UTF_8) : new byte[0];
            buf.putInt(name.length);
            if (name.length > 0) buf.put(name);
            buf.putInt(it.count());
            buf.putInt(it.ageSeconds());
        }
        buf.putFloat(0.98f); buf.putFloat(0.98f);
        buf.putInt(6000); buf.putInt(20);
        buf.flip();
        return buf;
    }

    public static List<com.kneaf.core.data.ItemEntityData> deserializeItemProcessResult(ByteBuffer buffer) {
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        buffer.rewind();
        if (buffer.remaining() < 8 + 4) return List.of();
        buffer.getLong(); // consume tickCount header
        int num = buffer.getInt();
        if (num < 0 || num > 1_000_000) return List.of();
        List<com.kneaf.core.data.ItemEntityData> out = new ArrayList<>(Math.max(0, num));
        for (int i = 0; i < num; i++) {
            if (buffer.remaining() < 8 + 4 + 4 + 4 + 4) break;
            long id = buffer.getLong();
            int chunkX = buffer.getInt();
            int chunkZ = buffer.getInt();
            int nameLen = buffer.getInt();
            String name = "";
            if (nameLen > 0 && buffer.remaining() >= nameLen) {
                byte[] nb = new byte[nameLen];
                buffer.get(nb);
                name = new String(nb, java.nio.charset.StandardCharsets.UTF_8);
            } else if (nameLen > 0) {
                buffer.position(buffer.limit());
            }
            int count = buffer.remaining() >= 4 ? buffer.getInt() : 0;
            int ageSeconds = buffer.remaining() >= 4 ? buffer.getInt() : 0;
            out.add(new com.kneaf.core.data.ItemEntityData(id, chunkX, chunkZ, name, count, ageSeconds));
        }
        return out;
    }

    public static ByteBuffer serializeMobInput(long tickCount, List<com.kneaf.core.data.MobData> mobs) {
        int base = 8 + 4 + 16; // tick + num + config
        int payload = 0;
        for (com.kneaf.core.data.MobData m : mobs) {
            payload += 8 + 4 + 1 + 4; // id + distance + passive byte + entityTypeLen
            String t = m.entityType(); if (t != null) payload += t.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        }
        ByteBuffer buf = ByteBuffer.allocateDirect(base + payload).order(ByteOrder.LITTLE_ENDIAN);
        buf.putLong(tickCount);
        buf.putInt(mobs.size());
        for (com.kneaf.core.data.MobData m : mobs) {
            buf.putLong(m.id());
            buf.putFloat((float) m.distance());
            buf.put((byte) (m.isPassive() ? 1 : 0));
            byte[] nm = m.entityType() != null ? m.entityType().getBytes(java.nio.charset.StandardCharsets.UTF_8) : new byte[0];
            buf.putInt(nm.length);
            if (nm.length > 0) buf.put(nm);
        }
        buf.putFloat(16.0f); buf.putFloat(32.0f); buf.putFloat(1.0f); buf.putFloat(0.5f);
        buf.flip();
        return buf;
    }

    public static List<com.kneaf.core.data.MobData> deserializeMobProcessResult(ByteBuffer buffer) {
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        buffer.rewind();
        if (buffer.remaining() < 8 + 4) return List.of();
        buffer.getLong(); // consume tickCount header
        int num = buffer.getInt();
        if (num < 0 || num > 1_000_000) return List.of();
        List<com.kneaf.core.data.MobData> out = new ArrayList<>(Math.max(0, num));
        for (int i = 0; i < num; i++) {
            if (buffer.remaining() < 8 + 4 + 1 + 4) break;
            long id = buffer.getLong();
            float distance = buffer.getFloat();
            byte passiveB = buffer.get();
            boolean isPassive = passiveB != 0;
            int typeLen = buffer.getInt();
            String type = "";
            if (typeLen > 0 && buffer.remaining() >= typeLen) {
                byte[] tb = new byte[typeLen];
                buffer.get(tb);
                type = new String(tb, java.nio.charset.StandardCharsets.UTF_8);
            } else if (typeLen > 0) {
                buffer.position(buffer.limit());
            }
            out.add(new com.kneaf.core.data.MobData(id, distance, isPassive, type));
        }
        return out;
    }

    public static ByteBuffer serializeBlockInput(long tickCount, List<com.kneaf.core.data.BlockEntityData> blocks) {
        int base = 8 + 4 + 24; // tick + num + config
        int payload = 0;
        for (com.kneaf.core.data.BlockEntityData b : blocks) {
            payload += 8 + 4 + 4 + 4 + 4 + 4; // id + distance + blockTypeLen + x + y + z
            String t = b.blockType(); if (t != null) payload += t.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        }
        ByteBuffer buf = ByteBuffer.allocateDirect(base + payload).order(ByteOrder.LITTLE_ENDIAN);
        buf.putLong(tickCount);
        buf.putInt(blocks.size());
        for (com.kneaf.core.data.BlockEntityData b : blocks) {
            buf.putLong(b.id());
            buf.putFloat((float) b.distance());
            byte[] nm = b.blockType() != null ? b.blockType().getBytes(java.nio.charset.StandardCharsets.UTF_8) : new byte[0];
            buf.putInt(nm.length); if (nm.length > 0) buf.put(nm);
            buf.putInt(b.x()); buf.putInt(b.y()); buf.putInt(b.z());
        }
        buf.putFloat(0.1f); buf.putFloat(0.05f);
        buf.putInt(8); buf.putInt(16);
        buf.flip();
        return buf;
    }

    public static ByteBuffer serializeVillagerInput(long tickCount, List<com.kneaf.core.data.VillagerData> villagers) {
        int base = 8 + 4 + 32; // tick + num + config (8 floats)
        int payload = 0;
        for (com.kneaf.core.data.VillagerData v : villagers) {
            payload += 8 + 4 + 4 + 4 + 4 + 1 + 1 + 4; // id + x + y + z + distance + profession + level + typeLen
            String t = v.getProfession(); if (t != null) payload += t.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        }
        ByteBuffer buf = ByteBuffer.allocateDirect(base + payload).order(ByteOrder.LITTLE_ENDIAN);
        buf.putLong(tickCount);
        buf.putInt(villagers.size());
        for (com.kneaf.core.data.VillagerData v : villagers) {
            buf.putLong(v.getId());
            buf.putFloat((float) v.getX());
            buf.putFloat((float) v.getY());
            buf.putFloat((float) v.getZ());
            buf.putFloat((float) v.getDistance());
            buf.put((byte) v.getLevel());
            buf.put((byte) (v.hasWorkstation() ? 1 : 0));
            byte[] typeBytes = v.getProfession() != null ? v.getProfession().getBytes(java.nio.charset.StandardCharsets.UTF_8) : new byte[0];
            buf.putInt(typeBytes.length);
            if (typeBytes.length > 0) buf.put(typeBytes);
        }
        // Config: disableDistance, simplifyDistance, reducePathfindDistance, pathfindFrequency, aiTickRate, maxGroupSize, spatialChunkSize, processingBudget
        buf.putFloat(150.0f); buf.putFloat(80.0f); buf.putFloat(40.0f); buf.putFloat(0.5f);
        buf.putFloat(1.0f); buf.putFloat(8.0f); buf.putFloat(16.0f); buf.putFloat(100.0f);
        buf.flip();
        return buf;
    }
}
