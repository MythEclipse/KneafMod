package com.kneaf.core;

import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.ModConfigSpec.ConfigValue;

// Configuration class for KneafCore mod.
// Handles performance-related settings and mod configuration.
public class Config {
  private Config() {}

  private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

  public static final ModConfigSpec.BooleanValue LOG_DIRT_BLOCK =
      BUILDER.comment("Whether to log the dirt block on common setup").define("logDirtBlock", true);

  public static final ModConfigSpec.IntValue MAGIC_NUMBER =
      BUILDER.comment("A magic number").defineInRange("magicNumber", 42, 0, Integer.MAX_VALUE);

  public static final ModConfigSpec.ConfigValue<String> MAGIC_NUMBER_INTRODUCTION =
      BUILDER
          .comment("What you want the introduction message to be for the magic number")
          .define("magicNumberIntroduction", "The magic number is... ");

  // a list of strings that are treated as resource locations for items
  // NOSONAR - false positive, the lambda is not a constant expressiona
  public static final ConfigValue<List<? extends String>> ITEM_STRINGS =
      BUILDER // NOSONAR
          .comment("A list of items to log on common setup.")
          .defineListAllowEmpty(
              "items",
              List.of("minecraft:iron_ingot"),
              () -> "",
              Config::validateItemName); // NOSONAR

  static final ModConfigSpec SPEC = BUILDER.build();

  private static boolean validateItemName(final Object obj) {
    return obj instanceof String itemName
        && BuiltInRegistries.ITEM.containsKey(ResourceLocation.parse(itemName));
  }
}
