package com.kneaf.core.flatbuffers.entity;

import com.kneaf.core.data.entity.EntityData;
import com.kneaf.core.data.entity.PlayerData;
import com.kneaf.core.flatbuffers.core.BaseFlatBufferSerializer;
import com.kneaf.core.flatbuffers.core.SerializationConstants;
import com.kneaf.core.flatbuffers.core.SerializationUtils;
import com.kneaf.core.flatbuffers.utils.BufferPool;
import com.kneaf.core.flatbuffers.utils.SchemaValidator;
import com.kneaf.core.flatbuffers.utils.SerializationException;
import java.nio.ByteBuffer;
import java.util.List;

/**
 * Serializer for entity data using flatbuffer format. Handles serialization and deserialization of
 * entity lists with player context.
 */
public class EntitySerializer extends BaseFlatBufferSerializer<EntityInput, List<Long>> {

  /** Create a new entity serializer with default configuration. */
  public EntitySerializer() {
    super("entity", SerializationConstants.SCHEMA_VERSION_1_0);
  }

  /**
   * Create a new entity serializer with custom buffer pool.
   *
   * @param bufferPool the buffer pool to use
   */
  public EntitySerializer(BufferPool bufferPool) {
    super("entity", SerializationConstants.SCHEMA_VERSION_1_0, bufferPool);
  }

  /**
   * Create a new entity serializer with full configuration.
   *
   * @param bufferPool the buffer pool to use
   * @param validator the schema validator to use
   */
  public EntitySerializer(BufferPool bufferPool, SchemaValidator<EntityInput> validator) {
    super("entity", SerializationConstants.SCHEMA_VERSION_1_0, bufferPool, validator);
  }

  @Override
  protected void serializeToBufferInternal(EntityInput input, ByteBuffer buffer)
      throws SerializationException {
    if (input == null) {
      throw new SerializationException(
          "Entity input cannot be null", getSerializerType(), "serializeToBufferInternal");
    }

    try {
      // Write tick count (8 bytes)
      buffer.putLong(input.tickCount);

      // Write entity count (4 bytes)
      List<EntityData> entities = input.entities;
      buffer.putInt(entities.size());

      // Write entity data
      for (EntityData entity : entities) {
        writeEntityData(buffer, entity);
      }

      // Write player count (4 bytes)
      List<PlayerData> players = input.players;
      buffer.putInt(players.size());

      // Write player data
      for (PlayerData player : players) {
        writePlayerData(buffer, player);
      }

      // Write entity config (5 floats = 20 bytes)
      writeEntityConfig(buffer);

    } catch (Exception e) {
      throw new SerializationException(
          "Failed to serialize entity input",
          e,
          getSerializerType(),
          "serializeToBufferInternal",
          buffer.array());
    }
  }

  @Override
  protected List<Long> deserializeFromBufferInternal(ByteBuffer buffer)
      throws SerializationException {
    try {
      // Read tick count (8 bytes)
      long tickCount = buffer.getLong();

      // Read entity count (4 bytes)
      int entityCount = buffer.getInt();
      if (entityCount < 0 || entityCount > SerializationConstants.MAX_ENTITY_COUNT) {
        throw new SerializationException(
            "Invalid entity count: " + entityCount,
            getSerializerType(),
            "deserializeFromBufferInternal");
      }

      // Read entity IDs to tick
      List<Long> entitiesToTick = new java.util.ArrayList<>(entityCount);
      for (int i = 0; i < entityCount; i++) {
        // Skip entity data for now, just extract IDs
        skipEntityData(buffer);
        // In a real implementation, we would process and filter entities
        entitiesToTick.add((long) i); // Placeholder
      }

      // Read player count (4 bytes)
      int playerCount = buffer.getInt();
      if (playerCount < 0 || playerCount > SerializationConstants.MAX_ENTITY_COUNT) {
        throw new SerializationException(
            "Invalid player count: " + playerCount,
            getSerializerType(),
            "deserializeFromBufferInternal");
      }

      // Skip player data for now
      for (int i = 0; i < playerCount; i++) {
        skipPlayerData(buffer);
      }

      // Skip entity config
      skipEntityConfig(buffer);

      return entitiesToTick;

    } catch (SerializationException e) {
      throw e;
    } catch (Exception e) {
      throw new SerializationException(
          "Failed to deserialize entity result",
          e,
          getSerializerType(),
          "deserializeFromBufferInternal",
          null);
    }
  }

  /**
   * Write entity data to buffer.
   *
   * @param buffer the buffer to write to
   * @param entity the entity data
   */
  private void writeEntityData(ByteBuffer buffer, EntityData entity) {
    buffer.putLong(entity.getId());
    buffer.putFloat((float) entity.getX());
    buffer.putFloat((float) entity.getY());
    buffer.putFloat((float) entity.getZ());
    buffer.putFloat((float) entity.getDistance());
    SerializationUtils.writeBoolean(buffer, entity.isBlockEntity());
    SerializationUtils.writeString(buffer, entity.getType());
  }

  /**
   * Write player data to buffer.
   *
   * @param buffer the buffer to write to
   * @param player the player data
   */
  private void writePlayerData(ByteBuffer buffer, PlayerData player) {
    buffer.putLong(player.getId());
    buffer.putFloat((float) player.getX());
    buffer.putFloat((float) player.getY());
    buffer.putFloat((float) player.getZ());
  }

  /**
   * Write entity configuration to buffer.
   *
   * @param buffer the buffer to write to
   */
  private void writeEntityConfig(ByteBuffer buffer) {
    // Default entity config values (5 floats = 20 bytes)
    buffer.putFloat(16.0f); // closeRadius
    buffer.putFloat(32.0f); // mediumRadius
    buffer.putFloat(1.0f); // closeRate
    buffer.putFloat(0.5f); // mediumRate
    buffer.putFloat(0.1f); // farRate
  }

  /**
   * Skip entity data in buffer (used during deserialization).
   *
   * @param buffer the buffer to skip data from
   */
  private void skipEntityData(ByteBuffer buffer) {
    buffer.position(buffer.position() + 8 + 4 + 4 + 4 + 4 + 1); // Skip basic fields
    int typeLength = buffer.getInt();
    buffer.position(buffer.position() + typeLength); // Skip string
  }

  /**
   * Skip player data in buffer (used during deserialization).
   *
   * @param buffer the buffer to skip data from
   */
  private void skipPlayerData(ByteBuffer buffer) {
    buffer.position(buffer.position() + 8 + 4 + 4 + 4); // Skip all player fields
  }

  /**
   * Skip entity configuration in buffer (used during deserialization).
   *
   * @param buffer the buffer to skip data from
   */
  private void skipEntityConfig(ByteBuffer buffer) {
    buffer.position(buffer.position() + 5 * 4); // Skip 5 floats
  }
}
