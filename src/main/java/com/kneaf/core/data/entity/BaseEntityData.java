package com.kneaf.core.data.entity;

import com.kneaf.core.data.core.DataEntity;
import com.kneaf.core.data.core.DataUtils;
import com.kneaf.core.data.core.Positionable;

/** Base class for entity data with common fields and validation. */
public abstract class BaseEntityData implements DataEntity, Positionable {

  protected final long id;
  protected final double x;
  protected final double y;
  protected final double z;
  protected final String entityType;

  /**
   * Creates a new base entity data instance.
   *
   * @param id the entity ID
   * @param x the X coordinate
   * @param y the Y coordinate
   * @param z the Z coordinate
   * @param entityType the entity type
   * @throws com.kneaf.core.data.core.DataValidationException if validation fails
   */
  protected BaseEntityData(long id, double x, double y, double z, String entityType) {
    this.id = id;
    this.x = x;
    this.y = y;
    this.z = z;
    this.entityType = entityType;
    // Do not call validate() here because subclasses may not have been
    // fully initialized yet. Validation should be performed explicitly
    // after construction if required.
  }

  @Override
  public long getId() {
    return id;
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
  public String getType() {
    return entityType;
  }

  @Override
  public boolean validate() {
    DataUtils.validateNonNegative(id, "id");
    DataUtils.validateCoordinate(x, "x");
    DataUtils.validateCoordinate(y, "y");
    DataUtils.validateCoordinate(z, "z");
    DataUtils.validateNotEmpty(entityType, "entityType");
    return true;
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) return true;
    if (obj == null || getClass() != obj.getClass()) return false;

    BaseEntityData that = (BaseEntityData) obj;
    return id == that.id;
  }

  @Override
  public int hashCode() {
    return Long.hashCode(id);
  }

  @Override
  public String toString() {
    return String.format(
        "%s{id=%d, x=%.2f, y=%.2f, z=%.2f, type='%s'}",
        getClass().getSimpleName(), id, x, y, z, entityType);
  }
}
