package com.kneaf.core.flatbuffers.block;

import com.kneaf.core.data.block.BlockEntityData;
import com.kneaf.core.flatbuffers.core.BaseFlatBufferSerializer;
import com.kneaf.core.flatbuffers.core.SerializationConstants;
import com.kneaf.core.flatbuffers.core.SerializationUtils;
import com.kneaf.core.flatbuffers.utils.BufferPool;
import com.kneaf.core.flatbuffers.utils.SchemaValidator;
import com.kneaf.core.flatbuffers.utils.SerializationException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Serializer for block entity data using flatbuffer format. Handles serialization and
 * deserialization of block entity processing results.
 */
public class BlockSerializer extends BaseFlatBufferSerializer<BlockInput, List<Long>> {

  /** Create a new block serializer with default configuration. */
  public BlockSerializer() {
    super(SerializationConstants.ENTITY_TYPE_BLOCK, SerializationConstants.SCHEMA_VERSION_1_0);
  }

  /**
   * Create a new block serializer with custom buffer pool.
   *
   * @param bufferPool the buffer pool to use
   */
  public BlockSerializer(BufferPool bufferPool) {
    super(
        SerializationConstants.ENTITY_TYPE_BLOCK,
        SerializationConstants.SCHEMA_VERSION_1_0,
        bufferPool);
  }

  /**
   * Create a new block serializer with full configuration.
   *
   * @param bufferPool the buffer pool to use
   * @param validator the schema validator to use
   */
  public BlockSerializer(BufferPool bufferPool, SchemaValidator<BlockInput> validator) {
    super(
        SerializationConstants.ENTITY_TYPE_BLOCK,
        SerializationConstants.SCHEMA_VERSION_1_0,
        bufferPool,
        validator);
  }

  @Override
  protected void serializeToBufferInternal(BlockInput input, ByteBuffer buffer)
      throws SerializationException {
    if (input == null) {
      throw new SerializationException(
          "Block input cannot be null", getSerializerType(), "serializeToBufferInternal");
    }

    try {
      // Write tick count (8 bytes)
      buffer.putLong(input.tickCount);

      // Write block entity count (4 bytes)
      List<BlockEntityData> blockEntities = input.blockEntities;
      buffer.putInt(blockEntities.size());

      // Write block entity data
      for (BlockEntityData blockEntity : blockEntities) {
        writeBlockEntityData(buffer, blockEntity);
      }

    } catch (Exception e) {
      throw new SerializationException(
          "Failed to serialize block input",
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
      // Read tick count (8 bytes) - placeholder, not used in result
      long tickCount = buffer.getLong();

      // Read block entity count (4 bytes)
      int blockEntityCount = buffer.getInt();
      if (blockEntityCount < 0) {
        throw new SerializationException(
            "Invalid block entity count: " + blockEntityCount,
            getSerializerType(),
            "deserializeFromBufferInternal");
      }

      // Read block entity IDs to tick
      List<Long> blockEntitiesToTick = new ArrayList<>(blockEntityCount);
      for (int i = 0; i < blockEntityCount; i++) {
        blockEntitiesToTick.add(buffer.getLong());
      }

      return blockEntitiesToTick;

    } catch (SerializationException e) {
      throw e;
    } catch (Exception e) {
      throw new SerializationException(
          "Failed to deserialize block result",
          e,
          getSerializerType(),
          "deserializeFromBufferInternal",
          null);
    }
  }

  /**
   * Write block entity data to buffer.
   *
   * @param buffer the buffer to write to
   * @param blockEntity the block entity data
   */
  private void writeBlockEntityData(ByteBuffer buffer, BlockEntityData blockEntity) {
    buffer.putLong(blockEntity.getId());
    buffer.putFloat((float) blockEntity.getDistance());
    SerializationUtils.writeString(buffer, blockEntity.getBlockType());
    buffer.putInt((int) blockEntity.getX());
    buffer.putInt((int) blockEntity.getY());
    buffer.putInt((int) blockEntity.getZ());
  }
}
