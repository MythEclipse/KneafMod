package com.kneaf.core.compatibility;

import net.neoforged.fml.ModList;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

import java.util.HashMap;
import java.util.Map;

/**
 * Utility class for checking compatibility with other performance mods.
 * Detects installed mods that may conflict with KneafMod features and logs warnings.
 */
public class ModCompatibility {
    private static final Logger LOGGER = LogUtils.getLogger();

    // Known performance mods that may conflict
    private static final String[] PERFORMANCE_MODS = {
        "lithium",      // Tick optimization
        "starlight",    // Lighting engine
        "ferritecore"   // Memory optimization
    };

    // Map of mod IDs to their potential conflicts
    private static final Map<String, String> CONFLICT_DESCRIPTIONS = Map.of(
        "lithium", "Lithium optimizes entity ticking, which may conflict with KneafMod's parallel processing optimizations.",
        "starlight", "Starlight replaces the lighting engine, which may interfere with performance optimizations.",
        "ferritecore", "FerriteCore optimizes memory usage, which may conflict with entity data management."
    );

    /**
     * Checks for loaded performance mods and logs warnings if conflicts are detected.
     * @return Map of detected conflicting mods and their descriptions
     */
    public static Map<String, String> checkForConflicts() {
        Map<String, String> conflicts = new HashMap<>();

        for (String modId : PERFORMANCE_MODS) {
            if (ModList.get().isLoaded(modId)) {
                String description = CONFLICT_DESCRIPTIONS.getOrDefault(modId, "Unknown conflict with " + modId);
                conflicts.put(modId, description);
                LOGGER.warn("Detected potentially conflicting mod: {} - {}", modId, description);
            }
        }

        if (conflicts.isEmpty()) {
            LOGGER.info("No conflicting performance mods detected.");
        } else {
            LOGGER.warn("Found {} potentially conflicting performance mod(s). Consider reviewing configuration.", conflicts.size());
        }

        return conflicts;
    }

    /**
     * Checks if a specific mod is loaded.
     * @param modId The mod ID to check
     * @return true if the mod is loaded
     */
    public static boolean isModLoaded(String modId) {
        return ModList.get().isLoaded(modId);
    }
}