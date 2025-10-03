package com.kneaf.core.performance.core;

import java.util.List;

/**
 * Result of villager processing operation.
 */
public class VillagerProcessResult {
    private final List<Long> villagersToDisableAI;
    private final List<Long> villagersToSimplifyAI;
    private final List<Long> villagersToReducePathfinding;

    public VillagerProcessResult(List<Long> villagersToDisableAI, List<Long> villagersToSimplifyAI, 
                               List<Long> villagersToReducePathfinding) {
        this.villagersToDisableAI = villagersToDisableAI;
        this.villagersToSimplifyAI = villagersToSimplifyAI;
        this.villagersToReducePathfinding = villagersToReducePathfinding;
    }

    public List<Long> getVillagersToDisableAI() {
        return villagersToDisableAI;
    }

    public List<Long> getVillagersToSimplifyAI() {
        return villagersToSimplifyAI;
    }

    public List<Long> getVillagersToReducePathfinding() {
        return villagersToReducePathfinding;
    }
}