package com.kneaf.core.registry;

import com.kneaf.core.KneafCore;
import net.minecraft.world.level.block.Blocks;

public final class ModBlocks {
  private ModBlocks() {}

  static {
    // Register a simple example ore block as an alias to stone for compatibility.
    KneafCore.BLOCKS.register("example_ore", () -> Blocks.STONE);
  }
}
