package com.kneaf.core.binary.core;

import com.kneaf.core.binary.utils.BufferPool;
import com.kneaf.core.binary.utils.SchemaValidator;
import com.kneaf.core.binary.utils.SerializationException;
import java.nio.ByteBuffer;

/**
 * Abstract base class for binary serializers. Provides common functionality and buffer management
 * for all serializers.
 */
public abstract class BaseBinarySerializer<T, R> implements BinarySerializer<T, R> {

  protected final BufferPool bufferPool;
  protected final SchemaValidator<T> validator;
  protected final String serializerType;
  protected final String schemaVersion;

  /**
   * Create a new base serializer with default configuration.
   *
   * @param serializerType the type identifier for this serializer
   * @param schemaVersion the schema version this serializer supports
   */
  protected BaseBinarySerializer(String serializerType, String schemaVersion) {
    this(serializerType, schemaVersion, new BufferPool(), null);
  }

  /**
   * Create a new base serializer with custom buffer pool.
   *
   * @param serializerType the type identifier for this serializer
   * @param schemaVersion the schema version this serializer supports
   * @param bufferPool the buffer pool to use
   */
  protected BaseBinarySerializer(
      String serializerType, String schemaVersion, BufferPool bufferPool) {
    this(serializerType, schemaVersion, bufferPool, null);
  }

  /**
   * Create a new base serializer with full configuration.
   *
   * @param serializerType the type identifier for this serializer
   * @param schemaVersion the schema version this serializer supports
   * @param bufferPool the buffer pool to use
   * @param validator the schema validator to use
   */
  protected BaseBinarySerializer(
      String serializerType,
      String schemaVersion,
      BufferPool bufferPool,
      SchemaValidator<T> validator) {
    if (serializerType == null || serializerType.trim().isEmpty()) {
      throw new IllegalArgumentException("Serializer type cannot be null or empty");
    }
    if (schemaVersion == null || schemaVersion.trim().isEmpty()) {
      throw new IllegalArgumentException("Schema version cannot be null or empty");
    }

    this.serializerType = serializerType;
    this.schemaVersion = schemaVersion;
    this.bufferPool = bufferPool != null ? bufferPool : new BufferPool();
    this.validator = validator;
  }

  @Override
  public byte[] serialize(T input) throws SerializationException {
    if (input == null) {
      throw new SerializationException("Input cannot be null", serializerType, "serialize");
    }

    // Validate input if validator is available
    if (validator != null && !validator.validate(input).isValid()) {
      throw new SerializationException("Input validation failed", serializerType, "serialize");
    }

    ByteBuffer buffer = null;
    try {
      buffer = bufferPool.borrowBuffer();
      serializeToBufferInternal(input, buffer);

      // Extract data from buffer
      byte[] result = new byte[buffer.position()];
      buffer.flip();
      buffer.get(result);

      return result;
    } catch (Exception e) {
      throw new SerializationException(
          "Serialization failed",
          e,
          serializerType,
          "serialize",
          buffer != null ? buffer.array() : null);
    } finally {
      if (buffer != null) {
        bufferPool.returnBuffer(buffer);
      }
    }
  }

  @Override
  public R deserialize(byte[] data) throws SerializationException {
    if (data == null) {
      throw new SerializationException("Data cannot be null", serializerType, "deserialize");
    }

    if (data.length == 0) {
      throw new SerializationException("Data cannot be empty", serializerType, "deserialize");
    }

    ByteBuffer buffer = null;
    try {
      buffer = SerializationUtils.wrapBuffer(data);
      return deserializeFromBuffer(buffer);
    } catch (SerializationException e) {
      throw e; // Re-throw serialization exceptions
    } catch (Exception e) {
      throw new SerializationException(
          "Deserialization failed", e, serializerType, "deserialize", data);
    } finally {
      // Note: We don't return this buffer to pool since it was created from data
    }
  }

  @Override
  public ByteBuffer serializeToBuffer(T input) throws SerializationException {
    if (input == null) {
      throw new SerializationException("Input cannot be null", serializerType, "serializeToBuffer");
    }

    // Validate input if validator is available
    if (validator != null && !validator.validate(input).isValid()) {
      throw new SerializationException(
          "Input validation failed", serializerType, "serializeToBuffer");
    }

    ByteBuffer buffer = null;
    try {
      buffer = bufferPool.borrowBuffer();
      serializeToBufferInternal(input, buffer);
      return buffer;
    } catch (Exception e) {
      if (buffer != null) {
        bufferPool.returnBuffer(buffer);
      }
      throw new SerializationException(
          "Serialization to buffer failed",
          e,
          serializerType,
          "serializeToBuffer",
          buffer != null ? buffer.array() : null);
    }
  }

  @Override
  public R deserializeFromBuffer(ByteBuffer buffer) throws SerializationException {
    if (buffer == null) {
      throw new SerializationException(
          "Buffer cannot be null", serializerType, "deserializeFromBuffer");
    }

    try {
      return deserializeFromBufferInternal(buffer);
    } catch (SerializationException e) {
      throw e; // Re-throw serialization exceptions
    } catch (Exception e) {
      throw new SerializationException(
          "Deserialization from buffer failed", e, serializerType, "deserializeFromBuffer", null);
    }
  }

  @Override
  public String getSchemaVersion() {
    return schemaVersion;
  }

  @Override
  public boolean validateInput(T input) {
    if (validator == null) {
      return true; // No validator available, assume valid
    }
    return validator.validate(input).isValid();
  }

  @Override
  public String getSerializerType() {
    return serializerType;
  }

  /**
   * Internal method to serialize input to buffer. Subclasses must implement this method.
   *
   * @param input the input data
   * @param buffer the buffer to write to
   * @throws SerializationException if serialization fails
   */
  protected abstract void serializeToBufferInternal(T input, ByteBuffer buffer)
      throws SerializationException;

  /**
   * Internal method to deserialize from buffer. Subclasses must implement this method.
   *
   * @param buffer the buffer to read from
   * @return the deserialized data
   * @throws SerializationException if deserialization fails
   */
  protected abstract R deserializeFromBufferInternal(ByteBuffer buffer)
      throws SerializationException;

  /**
   * Get the buffer pool used by this serializer.
   *
   * @return the buffer pool
   */
  public BufferPool getBufferPool() {
    return bufferPool;
  }

  /**
   * Get the schema validator used by this serializer.
   *
   * @return the schema validator (may be null)
   */
  public SchemaValidator<T> getValidator() {
    return validator;
  }

  /**
   * Release resources used by this serializer. This should be called when the serializer is no
   * longer needed.
   */
  public void shutdown() {
    if (bufferPool != null) {
      bufferPool.clear();
    }
  }
}
