package com.kneaf.core.util;

import com.kneaf.core.model.EntityPhysicsData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility class for entity physics operations.
 * Consolidates physics data extraction, validation, and timeout calculations.
 */
public final class EntityPhysicsHelper {
    private static final Logger LOGGER = LoggerFactory.getLogger(EntityPhysicsHelper.class);

    // Timeout constants
    private static final int BASE_ASYNC_TIMEOUT_MS = 50;
    private static final int MIN_ASYNC_TIMEOUT_MS = 25;
    private static final int MAX_ASYNC_TIMEOUT_MS = 200;

    private EntityPhysicsHelper() {
        // Private constructor for utility class
    }

    /**
     * Extracts physics data (motion) from an entity.
     * Validates that the data contains valid numbers (not NaN or Infinite).
     *
     * @param entity The entity to extract data from.
     * @return EntityPhysicsData containing motion vectors, or null if
     *         invalid/error.
     */
    public static EntityPhysicsData extractPhysicsData(Entity entity) {
        try {
            Vec3 deltaMovement = entity.getDeltaMovement();
            double x = deltaMovement.x();
            double y = deltaMovement.y();
            double z = deltaMovement.z();

            if (!isValidDouble(x) || !isValidDouble(y) || !isValidDouble(z)) {
                return null;
            }

            return new EntityPhysicsData(x, y, z);
        } catch (Exception e) {
            LOGGER.error("Error extracting physics data: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Validates if a double is finite (not NaN and not Infinite).
     */
    public static boolean isValidDouble(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }

    /**
     * Validates that all components of the physics data are valid.
     */
    public static boolean isValidPhysicsData(EntityPhysicsData data) {
        if (data == null)
            return false;
        return isValidDouble(data.motionX) && isValidDouble(data.motionY) && isValidDouble(data.motionZ);
    }

    /**
     * Calculate adaptive timeout for async processing based on entity complexity.
     * Currently uses a simple range constraint.
     */
    public static int calculateAdaptiveTimeout(Entity entity) {
        // Logic extracted from OptimizationInjector
        // Future expansion: could take entity type complexity into account
        return Math.max(MIN_ASYNC_TIMEOUT_MS, Math.min(BASE_ASYNC_TIMEOUT_MS, MAX_ASYNC_TIMEOUT_MS)); // Simplifies to
                                                                                                      // BASE_ASYNC_TIMEOUT_MS
                                                                                                      // given current
                                                                                                      // constants, but
                                                                                                      // keeps logic
                                                                                                      // structure
    }

    /**
     * Checks if an entity is valid for optimization.
     * Prevents optimization of Items, Frames, etc., and allows only Vanilla or
     * KneafMod entities.
     */
    public static boolean isEntityOptimizable(Entity entity) {
        if (entity == null)
            return false;

        try {
            String entityClassName = entity.getClass().getName();

            // EXCLUDE ITEMS and non-living entities often involved in critical slight
            // movements
            if (entityClassName.contains(".item.") ||
                    entityClassName.contains("ItemEntity") ||
                    entityClassName.contains("ItemFrame") ||
                    entityClassName.contains("ItemStack")) {
                return false;
            }

            // WHITELIST APPROACH
            boolean isVanillaMinecraftEntity = entityClassName.startsWith("net.minecraft.world.entity.") ||
                    entityClassName.startsWith("net.minecraft.client.player.") ||
                    entityClassName.startsWith("net.minecraft.server.level.");

            boolean isKneafModEntity = entityClassName.startsWith("com.kneaf.entities.");

            // Only allow vanilla or our mod's entities
            return isVanillaMinecraftEntity || isKneafModEntity;
        } catch (Exception e) {
            return false;
        }
    }
}
