package com.kneaf.core.performance.spatial;

import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Optional;
import java.time.Instant;
import com.kneaf.core.data.entity.VillagerData;

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
  
  // Cache for villager position data to avoid repeated lookups
  private Map<Long, VillagerPosition> villagerPositionCache = new ConcurrentHashMap<>();

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
   * Gets the last known movement distance for the group.
   * @return Last known movement distance
   */
  public float getLastKnownMovement() {
    return lastKnownMovement;
  }

  /**
   * Internal class to store villager position data with timestamp
   */
  private static class VillagerPosition {
    private final double x;
    private final double y;
    private final double z;
    private final long lastUpdated;
    
    public VillagerPosition(double x, double y, double z) {
      this.x = x;
      this.y = y;
      this.z = z;
      this.lastUpdated = System.currentTimeMillis();
    }
    
    public double getX() { return x; }
    public double getY() { return y; }
    public double getZ() { return z; }
    public long getLastUpdated() { return lastUpdated; }
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

    // Get actual villager movement data and check for significant movement
    float actualMovement = calculateActualGroupMovement();
    
    // Update last check time
    this.lastMovementCheck = Instant.now();
    this.lastKnownMovement = actualMovement;
    
    // Use actual movement data with group type modifiers
    return switch (groupType) {
      case "breeding" -> actualMovement > movementThreshold * 0.7f;
      case "working" -> actualMovement > movementThreshold * 0.5f;
      case "village" -> actualMovement > movementThreshold * 0.3f;
      default -> actualMovement > movementThreshold;
    };
  }
  
  /**
   * Calculates actual movement for the entire villager group by comparing
   * current positions with cached positions.
   * @return Total movement distance for the group
   */
  private float calculateActualGroupMovement() {
    float totalMovement = 0.0f;
    Instant now = Instant.now();
    
    for (Long villagerId : villagerIds) {
      Optional<VillagerData> villagerDataOpt = getVillagerData(villagerId);
      
      if (villagerDataOpt.isPresent()) {
        VillagerData villagerData = villagerDataOpt.get();
        VillagerPosition cachedPosition = villagerPositionCache.get(villagerId);
        
        if (cachedPosition != null) {
          // Calculate distance moved from cached position
          float distance = calculateDistance(
            cachedPosition.getX(), cachedPosition.getY(), cachedPosition.getZ(),
            villagerData.getX(), villagerData.getY(), villagerData.getZ()
          );
          
          if (distance > 0.1f) { // Ignore tiny movements
            totalMovement += distance;
          }
        }
        
        // Update cache with current position
        villagerPositionCache.put(villagerId,
          new VillagerPosition(villagerData.getX(), villagerData.getY(), villagerData.getZ()));
      }
    }
    
    return totalMovement;
  }
  
  /**
   * Calculates 3D distance between two points
   * @param x1 X coordinate of first point
   * @param y1 Y coordinate of first point
   * @param z1 Z coordinate of first point
   * @param x2 X coordinate of second point
   * @param y2 Y coordinate of second point
   * @param z2 Z coordinate of second point
   * @return Distance between points
   */
  private float calculateDistance(double x1, double y1, double z1, double x2, double y2, double z2) {
    double dx = x2 - x1;
    double dy = y2 - y1;
    double dz = z2 - z1;
    return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
  }
  
  /**
   * Retrieves actual villager data from the game world.
   * In a real implementation, this would integrate with Minecraft's entity system.
   * @param villagerId ID of the villager to retrieve data for
   * @return Optional containing VillagerData if found, empty otherwise
   */
  protected Optional<VillagerData> getVillagerData(long villagerId) {
    // In a complete implementation, this would:
    // 1. Integrate with Minecraft's entity tracking system
    // 2. Retrieve the actual Villager entity from the world
    // 3. Return the current position and state data
    
    // For now, return empty as this would require game world integration
    // that's beyond the scope of this performance optimization class
    return Optional.empty();
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
