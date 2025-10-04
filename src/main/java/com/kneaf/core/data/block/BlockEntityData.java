package com.kneaf.core.data.block;

import com.kneaf.core.data.core.DataConstants;
import com.kneaf.core.data.core.DataEntity;
import com.kneaf.core.data.core.DataUtils;
import com.kneaf.core.data.core.Positionable;

/** Data class representing a block entity. */
public class BlockEntityData implements DataEntity, Positionable {

  private final long id;
  private final double distance;
  private final String blockType;
  private final int x;
  private final int y;
  private final int z;

  /**
   * Creates a new block entity data instance.
   *
   * @param id the block entity ID
   * @param distance the distance from reference point
   * @param blockType the block type
   * @param x the X coordinate
   * @param y the Y coordinate
   * @param z the Z coordinate
   * @throws com.kneaf.core.data.core.DataValidationException if validation fails
   */
  public BlockEntityData(long id, double distance, String blockType, int x, int y, int z) {
    this.id = id;
    this.distance = distance;
    this.blockType = blockType;
    this.x = x;
    this.y = y;
    this.z = z;

    validate();
  }

  @Override
  public long getId() {
    return id;
  }

  @Override
  public String getType() {
    return DataConstants.ENTITY_TYPE_BLOCK;
  }

  public double getDistance() {
    return distance;
  }

  public String getBlockType() {
    return blockType;
  }

  @Override
  public double getX() {
    return x;
  }

  @Override
  public double getY() {
    return y;
  }

  @Override
  public double getZ() {
    return z;
  }

  @Override
  public boolean validate() {
    DataUtils.validateNonNegative(id, "id");
    DataUtils.validateDistance(distance, "distance");
    DataUtils.validateNotEmpty(blockType, "blockType");
    return true;
  }

  /**
   * Gets the block position as a formatted string.
   *
   * @return formatted position string
   */
  public String getBlockPosition() {
    return String.format("(%d, %d, %d)", x, y, z);
  }

  /**
   * Checks if this block entity is within the specified distance.
   *
   * @param maxDistance the maximum distance
   * @return true if within distance
   */
  public boolean isWithinDistance(double maxDistance) {
    return distance <= maxDistance;
  }

  /**
   * Creates a builder for BlockEntityData.
   *
   * @return a new builder instance
   */
  public static Builder builder() {
    return new Builder();
  }

  /** Builder class for BlockEntityData. */
  public static class Builder {
    private long id;
    private double distance = DataConstants.DEFAULT_DISTANCE;
    private String blockType = "";
    private int x;
    private int y;
    private int z;

    public Builder id(long id) {
      this.id = id;
      return this;
    }

    public Builder distance(double distance) {
      this.distance = distance;
      return this;
    }

    public Builder blockType(String blockType) {
      this.blockType = blockType;
      return this;
    }

    public Builder position(int x, int y, int z) {
      this.x = x;
      this.y = y;
      this.z = z;
      return this;
    }

    public BlockEntityData build() {
      return new BlockEntityData(id, distance, blockType, x, y, z);
    }
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) return true;
    if (obj == null || getClass() != obj.getClass()) return false;

    BlockEntityData that = (BlockEntityData) obj;
    return id == that.id;
  }

  @Override
  public int hashCode() {
    return Long.hashCode(id);
  }

  @Override
  public String toString() {
    return String.format(
        "BlockEntityData{id=%d, distance=%.2f, type='%s', pos=(%d,%d,%d)}",
        id, distance, blockType, x, y, z);
  }
}
