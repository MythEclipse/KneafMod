package com.kneaf.core.data.core;

/**
 * Base interface for all data entities in the system. Provides common methods that all data
 * entities should implement.
 */
public interface DataEntity {

  /**
   * Gets the unique identifier for this entity.
   *
   * @return the entity ID
   */
  long getId();

  /**
   * Gets the type of this entity.
   *
   * @return the entity type as a string
   */
  String getType();

  /**
   * Validates the data integrity of this entity.
   *
   * @return true if the data is valid, false otherwise
   */
  boolean validate();
}
