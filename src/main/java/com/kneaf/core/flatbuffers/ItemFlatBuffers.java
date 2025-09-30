package com.kneaf.core.flatbuffers;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Manual binary serialization for ItemEntityData structures using ByteBuffer.
 */
public class ItemFlatBuffers {
    private ItemFlatBuffers() {}

    // Binary layout (little-endian):
    // [tickCount:long][numItems:int][items...][config: int,int,float,float]
    // Item layout: [id:long][chunkX:int][chunkZ:int][itemTypeLen:int][itemTypeBytes][count:int][ageSeconds:int]

    public static ByteBuffer serializeItemInput(long tickCount, List<com.kneaf.core.data.ItemEntityData> items) {
        int size = 8 + 4 + 16; // tick + num + config
        for (com.kneaf.core.data.ItemEntityData it : items) {
            byte[] itype = it.itemType() != null ? it.itemType().getBytes(StandardCharsets.UTF_8) : new byte[0];
            size += 8 + 4 + 4 + 4 + itype.length + 4 + 4;
        }
        ByteBuffer buf = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        buf.putLong(tickCount);
        buf.putInt(items.size());
        for (com.kneaf.core.data.ItemEntityData it : items) {
            buf.putLong(it.id());
            buf.putInt(it.chunkX());
            buf.putInt(it.chunkZ());
            byte[] itype = it.itemType() != null ? it.itemType().getBytes(StandardCharsets.UTF_8) : new byte[0];
            buf.putInt(itype.length);
            if (itype.length > 0) buf.put(itype);
            buf.putInt(it.count());
            buf.putInt(it.ageSeconds());
        }
        // config defaults
        buf.putInt(6000);
        buf.putInt(20);
        buf.putFloat(0.98f);
        buf.putFloat(0.98f);
        buf.flip();
        return buf;
    }

    public static List<com.kneaf.core.data.ItemEntityData> deserializeItemProcessResult(ByteBuffer buffer) {
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        buffer.rewind();
        int num = buffer.getInt();
        List<com.kneaf.core.data.ItemEntityData> out = new ArrayList<>();
        for (int i = 0; i < num; i++) {
            long id = buffer.getLong();
            int chunkX = buffer.getInt();
            int chunkZ = buffer.getInt();
            int itLen = buffer.getInt();
            String itemType = "";
            if (itLen > 0) {
                byte[] itb = new byte[itLen];
                buffer.get(itb);
                itemType = new String(itb, StandardCharsets.UTF_8);
            }
            int count = buffer.getInt();
            int age = buffer.getInt();
            out.add(new com.kneaf.core.data.ItemEntityData(id, chunkX, chunkZ, itemType, count, age));
        }
        return out;
    }
}