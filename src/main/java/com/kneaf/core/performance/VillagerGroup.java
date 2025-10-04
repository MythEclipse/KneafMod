package com.kneaf.core.performance;

import java.util.List;

/**
 * Compatibility shim so tests that reference com.kneaf.core.performance.VillagerGroup find a
 * concrete type. Delegates to the spatial implementation where possible.
 */
public class VillagerGroup extends com.kneaf.core.performance.spatial.VillagerGroup {
  public VillagerGroup(
      long groupId,
      float centerX,
      float centerY,
      float centerZ,
      List<Long> villagerIds,
      String groupType,
      byte aiTickRate) {
    super(groupId, centerX, centerY, centerZ, villagerIds, groupType, aiTickRate);
  }
}
