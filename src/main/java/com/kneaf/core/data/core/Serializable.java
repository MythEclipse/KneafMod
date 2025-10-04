package com.kneaf.core.data.core;

/** Interface for entities that can be serialized to binary format. */
public interface Serializable {

  /**
   * Serializes this entity to a byte array.
   *
   * @return the serialized byte array
   */
  byte[] serialize();

  /**
   * Deserializes an entity from a byte array.
   *
   * @param data the byte array to deserialize from
   * @return true if deserialization was successful, false otherwise
   */
  boolean deserialize(byte[] data);
}
