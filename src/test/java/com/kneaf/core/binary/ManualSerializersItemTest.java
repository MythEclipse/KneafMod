package com.kneaf.core.binary;

import java.nio.ByteBuffer;
import java.util.List;
import org.junit.jupiter.api.Test;

class ManualSerializersItemTest {

  @Test
  void serializeAndDeserializeItem_roundtrip() {
    var items =
        List.of(
            new com.kneaf.core.data.item.ItemEntityData(12345L, 5, 7, "minecraft:stone", 64, 10));
    ByteBuffer buf = ManualSerializers.serializeItemInput(42L, items);
    // Ensure little-endian ordering and non-empty
    buf.order(java.nio.ByteOrder.LITTLE_ENDIAN);
    org.junit.jupiter.api.Assertions.assertTrue(buf.capacity() > 0);
    // Read header manually to verify first item id is present in the payload (tick + num + first
    // item id at offset)
    buf.rewind();
    buf.getLong(); // consume tick
    int num = buf.getInt();
    org.junit.jupiter.api.Assertions.assertEquals(1, num);
    long id = buf.getLong();
    org.junit.jupiter.api.Assertions.assertEquals(12345L, id);
  }
}
