package com.kneaf.core.data;

/**
 * Compatibility shim for legacy import path com.kneaf.core.data.VillagerData.
 * Delegates to the real implementation in com.kneaf.core.data.entity.VillagerData.
 */
public class VillagerData extends com.kneaf.core.data.entity.VillagerData {

    public VillagerData(long id, double x, double y, double z, double distance,
                        String profession, int level, boolean hasWorkstation,
                        boolean isResting, boolean isBreeding, long lastPathfindTick,
                        int pathfindFrequency, int aiComplexity) {
        super(id, x, y, z, distance, profession, level, hasWorkstation,
              isResting, isBreeding, lastPathfindTick, pathfindFrequency, aiComplexity);
    }

    // Expose builder() for tests or callers that rely on the old package
    public static com.kneaf.core.data.entity.VillagerData.Builder builder() {
        return com.kneaf.core.data.entity.VillagerData.builder();
    }
}
