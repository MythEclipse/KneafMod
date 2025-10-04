package com.kneaf.core.data.entity;

import com.kneaf.core.data.core.DataConstants;

/** Data class representing a player entity. */
public class PlayerData extends BaseEntityData {

  /**
   * Creates a new player data instance.
   *
   * @param id the player ID
   * @param x the X coordinate
   * @param y the Y coordinate
   * @param z the Z coordinate
   * @throws com.kneaf.core.data.core.DataValidationException if validation fails
   */
  public PlayerData(long id, double x, double y, double z) {
    super(id, x, y, z, DataConstants.ENTITY_TYPE_PLAYER);
  }

  /**
   * Creates a builder for PlayerData.
   *
   * @return a new builder instance
   */
  public static Builder builder() {
    return new Builder();
  }

  /** Builder class for PlayerData. */
  public static class Builder {
    private long id;
    private double x;
    private double y;
    private double z;

    public Builder id(long id) {
      this.id = id;
      return this;
    }

    public Builder position(double x, double y, double z) {
      this.x = x;
      this.y = y;
      this.z = z;
      return this;
    }

    public PlayerData build() {
      return new PlayerData(id, x, y, z);
    }
  }
}
