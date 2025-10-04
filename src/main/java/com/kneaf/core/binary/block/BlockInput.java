package com.kneaf.core.binary.block;

import com.kneaf.core.data.block.BlockEntityData;
import java.util.List;

/** Helper class for block input data. */
public class BlockInput {
  public final long tickCount;
  public final List<BlockEntityData> blockEntities;

  public BlockInput(long tickCount, List<BlockEntityData> blockEntities) {
    this.tickCount = tickCount;
    this.blockEntities = blockEntities;
  }
}
