package com.kneaf.core.compatibility;

import com.mojang.logging.LogUtils;
import java.util.HashMap;
import java.util.Map;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;

/**
 * Utility class for checking compatibility with other performance mods. Detects installed mods that
 * may conflict with KneafMod features and logs warnings.
 */
public class ModCompatibility {
  private static final Logger LOGGER = LogUtils.getLogger();

  // Known performance mods that may conflict (treat these as potential conflicts)
  private static final String[] INCOMPATIBLE_MODS = {
    "lithium", // Tick optimization
    "starlight" // Lighting engine
  };

  // Known performance mods that are compatible/integrated with KneafMod
  private static final String[] COMPATIBLE_MODS = {
    "ferritecore" // Memory/pooling optimizations (compatible)
  };

  // Map of mod IDs to their potential conflicts
  private static final Map<String, String> CONFLICT_DESCRIPTIONS =
      Map.of(
          "lithium",
              "Lithium optimizes entity ticking, which may conflict with KneafMod's parallel processing optimizations.",
          "starlight",
              "Starlight replaces the lighting engine, which may interfere with performance optimizations.");

  // Map of compatible mod IDs to helpful information / integration notes
  private static final Map<String, String> COMPAT_DESCRIPTIONS =
      Map.of(
          "ferritecore",
          "FerriteCore provides memory/pooling optimizations and is compatible with KneafMod. When present, KneafMod will avoid duplicating pooling logic and prefers FerriteCore's implementations.");

  /**
   * Checks for loaded performance mods and logs warnings if conflicts are detected.
   *
   * @return Map of detected conflicting mods and their descriptions
   */
  public static Map<String, String> checkForConflicts() {
    Map<String, String> conflicts = new HashMap<>();

    for (String modId : INCOMPATIBLE_MODS) {
      if (ModList.get().isLoaded(modId)) {
        String description =
            CONFLICT_DESCRIPTIONS.getOrDefault(modId, "Unknown conflict with " + modId);
        conflicts.put(modId, description);
        LOGGER.warn("Detected potentially conflicting mod: { } - { }", modId, description);
      }
    }

    // If FerriteCore is present, treat it as compatible and log info instead of warning
    for (String modId : COMPATIBLE_MODS) {
      if (ModList.get().isLoaded(modId)) {
        String info = COMPAT_DESCRIPTIONS.getOrDefault(modId, "Detected compatible mod: " + modId);
        LOGGER.info("Detected compatible mod: { } - { }", modId, info);
      }
    }

    if (conflicts.isEmpty()) {
      LOGGER.info("No conflicting performance mods detected.");
    } else {
      LOGGER.warn(
          "Found { } potentially conflicting performance mod(s). Consider reviewing configuration.",
          conflicts.size());
    }

    return conflicts;
  }

  /**
   * Checks if a specific mod is loaded.
   *
   * @param modId The mod ID to check
   * @return true if the mod is loaded
   */
  public static boolean isModLoaded(String modId) {
    return ModList.get().isLoaded(modId);
  }
}
