/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 * 
 * Mod Compatibility - Detects other optimization mods to prevent conflicts.
 */
package com.kneaf.core;

import net.neoforged.fml.ModList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;

/**
 * ModCompatibility - Handles compatibility with other optimization mods.
 * 
 * Detects:
 * - Lithium (fabric/neoforge port)
 * - Sodium (rendering)
 * - C2ME (chunk gen)
 * - FerriteCore (memory)
 * - Starlight (lighting)
 */
public class ModCompatibility {

    private static final Logger LOGGER = LoggerFactory.getLogger("KneafMod/ModCompatibility");

    // Detected mods
    private static boolean isLithiumPresent = false;
    private static boolean isSodiumPresent = false;
    private static boolean isC2MEPresent = false;
    private static boolean isFerriteCorePresent = false;
    private static boolean isStarlightPresent = false;
    private static boolean isModernFixPresent = false;

    // Disabled mixins due to conflicts
    private static final Set<String> disabledOptimizations = new HashSet<>();

    /**
     * Initialize compatibility checks.
     */
    public static void init() {
        checkMods();
        applyCompatibilityRules();
        logCompatibilityReport();
    }

    /**
     * Check for presence of known optimization mods.
     */
    private static void checkMods() {
        ModList modList = ModList.get();

        isLithiumPresent = modList.isLoaded("lithium") || modList.isLoaded("radium") || modList.isLoaded("canary");
        isSodiumPresent = modList.isLoaded("sodium") || modList.isLoaded("rubidium") || modList.isLoaded("embeddium");
        isC2MEPresent = modList.isLoaded("c2me");
        isFerriteCorePresent = modList.isLoaded("ferritecore");
        isStarlightPresent = modList.isLoaded("starlight");
        isModernFixPresent = modList.isLoaded("modernfix");
    }

    /**
     * Apply rules to disable conflicting features.
     */
    private static void applyCompatibilityRules() {
        if (isLithiumPresent) {
            disableOptimization("EntityPhysics", "Lithium handles entity physics");
            disableOptimization("HopperOptimizations", "Lithium handles hoppers");
            disableOptimization("Pathfinding", "Lithium handles pathfinding");
        }

        if (isC2MEPresent) {
            disableOptimization("ChunkGen", "C2ME handles chunk generation");
            disableOptimization("IOThreading", "C2ME handles I/O threading");
        }

        if (isFerriteCorePresent) {
            disableOptimization("StringInterning", "FerriteCore handles memory dedup");
            disableOptimization("BlockStateCache", "FerriteCore handles block states");
        }

        if (isStarlightPresent) {
            disableOptimization("Lighting", "Starlight handles lighting engine");
        }

        if (isModernFixPresent) {
            disableOptimization("DynamicResources", "ModernFix handles resource reloading");
            disableOptimization("ObjectPooling", "ModernFix handles object reuse");
        }
    }

    /**
     * Disable a specific optimization category.
     */
    private static void disableOptimization(String feature, String reason) {
        disabledOptimizations.add(feature);
        LOGGER.warn("⚠️ Disabling KneafMod {} optimization: {}", feature, reason);
    }

    /**
     * Check if a feature should be enabled.
     */
    public static boolean isFeatureEnabled(String feature) {
        return !disabledOptimizations.contains(feature);
    }

    /**
     * Log final compatibility report.
     */
    private static void logCompatibilityReport() {
        LOGGER.info("=== Mod Compatibility Report ===");
        LOGGER.info("Lithium: {}", isLithiumPresent ? "DETECTED" : "Not Found");
        LOGGER.info("Sodium: {}", isSodiumPresent ? "DETECTED" : "Not Found");
        LOGGER.info("C2ME: {}", isC2MEPresent ? "DETECTED" : "Not Found");
        LOGGER.info("FerriteCore: {}", isFerriteCorePresent ? "DETECTED" : "Not Found");
        LOGGER.info("Starlight: {}", isStarlightPresent ? "DETECTED" : "Not Found");

        if (!disabledOptimizations.isEmpty()) {
            LOGGER.info("Disabled {} internal optimizations to prevent conflicts.", disabledOptimizations.size());
        } else {
            LOGGER.info("✅ No conflicts detected. All KneafMod optimizations active.");
        }
        LOGGER.info("================================");
    }
}
