package com.kneaf.core.performance;

import java.util.List;

/** Result of villager AI processing optimization. */
public class VillagerProcessResult {
  private final List<Long> villagersToDisableAI;
  private final List<Long> villagersToSimplifyAI;
  private final List<Long> villagersToReducePathfinding;
  private final List<VillagerGroup> villagerGroups;

  public VillagerProcessResult(
      List<Long> villagersToDisableAI,
      List<Long> villagersToSimplifyAI,
      List<Long> villagersToReducePathfinding,
      List<VillagerGroup> villagerGroups) {
    this.villagersToDisableAI = villagersToDisableAI;
    this.villagersToSimplifyAI = villagersToSimplifyAI;
    this.villagersToReducePathfinding = villagersToReducePathfinding;
    this.villagerGroups = villagerGroups;
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

  public List<VillagerGroup> getVillagerGroups() {
    return villagerGroups;
  }
}
