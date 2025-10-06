package com.kneaf.core.performance.spatial;

import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.time.Instant;

/**
 * Represents a group of villagers for optimized processing with hierarchical spatial grid support.
 * Maintains O(log n) complexity for grouping operations and supports lazy updates.
 */
public class VillagerGroup {
  private final long groupId;
  private final float centerX;
  private final float centerY;
  private final float centerZ;
  private final List<Long> villagerIds;
  private final String groupType;
  private final byte aiTickRate;
  private final int hierarchyLevel;
  private final long cellKey;
  private final Instant lastUpdateTime;
  private final float groupRadius;
  private final Map<String, Object> metadata;

  // Lazy update tracking for villager movement
  private boolean needsUpdate;
  private float lastKnownMovement;
  private Instant lastMovementCheck;

  /**
   * Creates a new VillagerGroup with hierarchical spatial grid optimization.
   *
   * @param groupId Unique identifier for the group
   * @param centerX X coordinate of group center
   * @param centerY Y coordinate of group center
   * @param centerZ Z coordinate of group center
   * @param villagerIds List of villager IDs in this group
   * @param groupType Type of villager group (working, breeding, resting, wandering, village)
   * @param aiTickRate AI update frequency (lower = more frequent updates)
   * @param hierarchyLevel Spatial grid hierarchy level (0 = coarsest, higher = finer)
   * @param cellKey Spatial cell key in the hierarchical grid
   * @param groupRadius Maximum radius of the villager group
   */
  public VillagerGroup(
      long groupId,
      float centerX,
      float centerY,
      float centerZ,
      List<Long> villagerIds,
      String groupType,
      byte aiTickRate,
      int hierarchyLevel,
      long cellKey,
      float groupRadius) {
    this.groupId = groupId;
    this.centerX = centerX;
    this.centerY = centerY;
    this.centerZ = centerZ;
    this.villagerIds = villagerIds;
    this.groupType = groupType;
    this.aiTickRate = aiTickRate;
    this.hierarchyLevel = hierarchyLevel;
    this.cellKey = cellKey;
    this.groupRadius = groupRadius;
    this.lastUpdateTime = Instant.now();
    this.metadata = new ConcurrentHashMap<>();
    this.needsUpdate = false;
    this.lastKnownMovement = 0.0f;
    this.lastMovementCheck = Instant.now();
  }

  /**
   * Creates a new VillagerGroup with minimal parameters (backward compatibility).
   */
  public VillagerGroup(
      long groupId,
      float centerX,
      float centerY,
      float centerZ,
      List<Long> villagerIds,
      String groupType,
      byte aiTickRate) {
    this(groupId, centerX, centerY, centerZ, villagerIds, groupType, aiTickRate, 0, 0, 16.0f);
  }

  // Getters
  public long getGroupId() {
    return groupId;
  }

  public float getCenterX() {
    return centerX;
  }

  public float getCenterY() {
    return centerY;
  }

  public float getCenterZ() {
    return centerZ;
  }

  public List<Long> getVillagerIds() {
    return villagerIds;
  }

  public String getGroupType() {
    return groupType;
  }

  public byte getAiTickRate() {
    return aiTickRate;
  }

  public int getHierarchyLevel() {
    return hierarchyLevel;
  }

  public long getCellKey() {
    return cellKey;
  }

  public float getGroupRadius() {
    return groupRadius;
  }

  public Instant getLastUpdateTime() {
    return lastUpdateTime;
  }

  public Map<String, Object> getMetadata() {
    return metadata;
  }

  /**
   * Checks if this group needs lazy update based on villager movement.
   * @param movementThreshold Minimum movement distance to trigger update
   * @return true if group needs update, false otherwise
   */
  public boolean needsUpdate(float movementThreshold) {
    if (needsUpdate) {
      return true;
    }

    // Check if enough time has passed since last movement check
    long secondsSinceCheck = Instant.now().minusSeconds(1).compareTo(lastMovementCheck);
    if (secondsSinceCheck > 0) {
      return false;
    }

    // In a real implementation, we would check actual villager movement data
    // For this optimization, we use a heuristic based on group type
    return switch (groupType) {
      case "breeding" -> lastKnownMovement > movementThreshold * 0.7f;
      case "working" -> lastKnownMovement > movementThreshold * 0.5f;
      case "village" -> lastKnownMovement > movementThreshold * 0.3f;
      default -> lastKnownMovement > movementThreshold;
    };
  }

  /**
   * Updates the group with new villager movement data.
   * @param villagerId ID of villager that moved
   * @param movementDistance Distance moved by the villager
   */
  public void updateMovement(long villagerId, float movementDistance) {
    if (movementDistance > lastKnownMovement) {
      lastKnownMovement = movementDistance;
      needsUpdate = true;
      lastMovementCheck = Instant.now();
    }

    // Update metadata with movement statistics
    metadata.compute("movementStats", (k, v) -> {
      Map<String, Float> stats = (Map<String, Float>) v;
      if (stats == null) {
        stats = new HashMap<>();
        stats.put("total", 0.0f);
        stats.put("max", 0.0f);
        stats.put("count", 0.0f);
      }
      stats.put("total", stats.get("total") + movementDistance);
      stats.put("max", Math.max(stats.get("max"), movementDistance));
      stats.put("count", stats.get("count") + 1);
      return stats;
    });
  }

  /**
   * Resets the update flag after processing.
   */
  public void resetUpdateFlag() {
    needsUpdate = false;
    lastKnownMovement = 0.0f;
  }

  /**
   * Calculates the optimal AI update rate based on current group characteristics.
   * @return Optimal AI tick rate (lower = more frequent)
   */
  public byte calculateOptimalAiTickRate() {
    // More compact groups get more frequent updates
    float density = villagerIds.size() / (float) (groupRadius * groupRadius * Math.PI);
    
    if (density > 0.5f) {
      return 1; // Very frequent updates for dense groups
    } else if (density > 0.2f) {
      return 2; // Frequent updates for medium density groups
    } else if (density > 0.1f) {
      return 4; // Regular updates for loose groups
    } else {
      return 8; // Minimal updates for sparse groups
    }
  }

  /**
   * Gets the spatial cell coordinates from the cell key.
   * @return Array of [x, y, z] coordinates in the spatial grid
   */
  public long[] getCellCoordinates() {
    // Decode the cell key into spatial coordinates (simplified implementation)
    return new long[] {
      cellKey >> 40,
      (cellKey >> 20) & 0xFFFFF,
      cellKey & 0xFFFFF
    };
  }

  /**
   * Checks if this group overlaps with another group in spatial space.
   * @param other The other group to check against
   * @return true if groups overlap, false otherwise
   */
  public boolean overlapsWith(VillagerGroup other) {
    float dx = this.centerX - other.centerX;
    float dy = this.centerY - other.centerY;
    float dz = this.centerZ - other.centerZ;
    float distance = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    
    return distance < (this.groupRadius + other.groupRadius) * 0.5f;
  }
}
