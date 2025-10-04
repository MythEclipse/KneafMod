package com.kneaf.core.data.core;

import java.util.UUID;

/** Interface for entities that have UUID identification. */
public interface Identifiable {

  /**
   * Gets the UUID of this entity.
   *
   * @return the UUID
   */
  UUID getUUID();
}
