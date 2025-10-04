package com.kneaf.core.flatbuffers.item;

import com.kneaf.core.data.item.ItemEntityData;
import java.util.List;

/** Helper class for item input data. */
public class ItemInput {
  public final long tickCount;
  public final List<ItemEntityData> items;

  public ItemInput(long tickCount, List<ItemEntityData> items) {
    this.tickCount = tickCount;
    this.items = items;
  }
}
