package com.kneaf.core.binary;

import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.util.List;

class ManualSerializersMobTest {

    @Test
    void serializeAndDeserializeMob_roundtrip() {
        var mobs = List.of(new com.kneaf.core.data.MobData(555L, 12.5f, false, "zombie"));
        ByteBuffer buf = ManualSerializers.serializeMobInput(100L, mobs);
        buf.order(java.nio.ByteOrder.LITTLE_ENDIAN);
        org.junit.jupiter.api.Assertions.assertTrue(buf.capacity() > 0);
        buf.rewind();
        buf.getLong(); // consume tick
        int num = buf.getInt();
        org.junit.jupiter.api.Assertions.assertEquals(1, num);
        long id = buf.getLong();
        org.junit.jupiter.api.Assertions.assertEquals(555L, id);
        float distance = buf.getFloat();
        org.junit.jupiter.api.Assertions.assertEquals(12.5f, distance);
    }
}
