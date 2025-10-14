package com.kneaf.core.binary.mob;

import com.kneaf.core.binary.core.BaseBinarySerializer;
import com.kneaf.core.binary.core.SerializationConstants;
import com.kneaf.core.binary.core.SerializationUtils;
import com.kneaf.core.binary.utils.BufferPool;
import com.kneaf.core.binary.utils.SchemaValidator;
import com.kneaf.core.binary.utils.SerializationException;
import com.kneaf.core.data.entity.MobData;
import com.kneaf.core.performance.core.MobProcessResult;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Serializer for mob data using flatbuffer format. Handles serialization and deserialization of mob
 * AI processing results.
 */
public class MobSerializer extends BaseBinarySerializer<MobInput, MobProcessResult> {

  /** Create a new mob serializer with default configuration. */
  public MobSerializer() {
    super(SerializationConstants.ENTITY_TYPE_MOB, SerializationConstants.SCHEMA_VERSION_1_0);
  }

  /**
   * Create a new mob serializer with custom buffer pool.
   *
   * @param bufferPool the buffer pool to use
   */
  public MobSerializer(BufferPool bufferPool) {
    super(
        SerializationConstants.ENTITY_TYPE_MOB,
        SerializationConstants.SCHEMA_VERSION_1_0,
        bufferPool);
  }

  /**
   * Create a new mob serializer with full configuration.
   *
   * @param bufferPool the buffer pool to use
   * @param validator the schema validator to use
   */
  public MobSerializer(BufferPool bufferPool, SchemaValidator<MobInput> validator) {
    super(
        SerializationConstants.ENTITY_TYPE_MOB,
        SerializationConstants.SCHEMA_VERSION_1_0,
        bufferPool,
        validator);
  }

  @Override
  protected void serializeToBufferInternal(MobInput input, ByteBuffer buffer)
      throws SerializationException {
    if (input == null) {
      throw new SerializationException(
          "Mob input cannot be null", getSerializerType(), "serializeToBufferInternal");
    }

    try {
      // Write tick count (8 bytes)
      buffer.putLong(input.tickCount);

      // Write mob count (4 bytes)
      List<MobData> mobs = input.mobs;
      buffer.putInt(mobs.size());

      // Write mob data
      for (MobData mob : mobs) {
        writeMobData(buffer, mob);
      }

    } catch (Exception e) {
      throw new SerializationException(
          "Failed to serialize mob input",
          e,
          getSerializerType(),
          "serializeToBufferInternal",
          buffer.array());
    }
  }

  @Override
  protected MobProcessResult deserializeFromBufferInternal(ByteBuffer buffer)
      throws SerializationException {
    try {
      // Read tick count (8 bytes) - placeholder, intentionally unused
      long _tickCount = buffer.getLong(); // placeholder to maintain wire format
      // Reference the placeholder to avoid unused-local warnings
      if (_tickCount == Long.MIN_VALUE) {
        // impossible, used only to silence static analysis
        throw new SerializationException(
            "Invalid tick count", getSerializerType(), "deserializeFromBufferInternal");
      }

      // Read disable list count (4 bytes)
      int disableCount = buffer.getInt();
      if (disableCount < 0) {
        throw new SerializationException(
            "Invalid disable count: " + disableCount,
            getSerializerType(),
            "deserializeFromBufferInternal");
      }

      // Read disable list
      List<Long> disableList = new ArrayList<>(disableCount);
      for (int i = 0; i < disableCount; i++) {
        disableList.add(buffer.getLong());
      }

      // Read simplify list count (4 bytes)
      int simplifyCount = buffer.getInt();
      if (simplifyCount < 0) {
        throw new SerializationException(
            "Invalid simplify count: " + simplifyCount,
            getSerializerType(),
            "deserializeFromBufferInternal");
      }

      // Read simplify list
      List<Long> simplifyList = new ArrayList<>(simplifyCount);
      for (int i = 0; i < simplifyCount; i++) {
        simplifyList.add(buffer.getLong());
      }

      // Build result using new builder pattern
      MobProcessResult.Builder builder = new MobProcessResult.Builder()
          .totalCount(disableList.size() + simplifyList.size())
          .processingTimeMs(0); // Not available in serialized data

      // Add disabled mobs
      for (Long mobId : disableList) {
        builder.addDespawnedMob(mobId);
      }

      // Add simplified mobs
      for (Long mobId : simplifyList) {
        builder.addSimplifiedMob(mobId);
      }

      return builder.build();

    } catch (SerializationException e) {
      throw e;
    } catch (Exception e) {
      throw new SerializationException(
          "Failed to deserialize mob result",
          e,
          getSerializerType(),
          "deserializeFromBufferInternal",
          null);
    }
  }

  /**
   * Write mob data to buffer.
   *
   * @param buffer the buffer to write to
   * @param mob the mob data
   */
  private void writeMobData(ByteBuffer buffer, MobData mob) {
    buffer.putLong(mob.getId());
    buffer.putFloat((float) mob.getDistance());
    SerializationUtils.writeBoolean(buffer, mob.isPassive());
    SerializationUtils.writeString(buffer, mob.getType());
  }
}
