package com.kneaf.core.flatbuffers;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Manual binary serialization for BlockEntityData structures using ByteBuffer.
 */
public class BlockFlatBuffers {
    private BlockFlatBuffers() {}

    // Binary layout (little-endian):
    // [tickCount:long][numBlocks:int][blocks...][config: 4 ints + 2 floats]
    // Block layout: [id:long][distance:float][blockTypeLen:int][blockTypeBytes][x:int][y:int][z:int]

    public static ByteBuffer serializeBlockInput(long tickCount, List<com.kneaf.core.data.BlockEntityData> blocks) {
        int size = 8 + 4 + 24; // tick + num + config estimate
        for (com.kneaf.core.data.BlockEntityData b : blocks) {
            byte[] bt = b.blockType() != null ? b.blockType().getBytes(StandardCharsets.UTF_8) : new byte[0];
            size += 8 + 4 + 4 + bt.length + 4 + 4; // id + distance + btLen + bytes + x + y + z
        }
        ByteBuffer buf = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        buf.putLong(tickCount);
        buf.putInt(blocks.size());
        for (com.kneaf.core.data.BlockEntityData b : blocks) {
            buf.putLong(b.id());
            buf.putFloat((float) b.distance());
            byte[] bt = b.blockType() != null ? b.blockType().getBytes(StandardCharsets.UTF_8) : new byte[0];
            buf.putInt(bt.length);
            if (bt.length > 0) buf.put(bt);
            buf.putInt(b.x());
            buf.putInt(b.y());
            buf.putInt(b.z());
        }
        // config defaults
        buf.putInt(8); // maxRedstonePower
        buf.putInt(16); // tickRadius
        buf.putFloat(0.1f); // tickChance
        buf.putFloat(0.05f); // updateChance
        buf.flip();
        return buf;
    }

    public static List<com.kneaf.core.data.BlockEntityData> deserializeBlockProcessResult(ByteBuffer buffer) {
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        buffer.rewind();
        int num = buffer.getInt();
        List<com.kneaf.core.data.BlockEntityData> out = new ArrayList<>();
        for (int i = 0; i < num; i++) {
            long id = buffer.getLong();
            float distance = buffer.getFloat();
            int btLen = buffer.getInt();
            String blockType = "";
            if (btLen > 0) {
                byte[] bt = new byte[btLen];
                buffer.get(bt);
                blockType = new String(bt, StandardCharsets.UTF_8);
            }
            int x = buffer.getInt();
            int y = buffer.getInt();
            int z = buffer.getInt();
            out.add(new com.kneaf.core.data.BlockEntityData(id, distance, blockType, x, y, z));
        }
        return out;
    }
}