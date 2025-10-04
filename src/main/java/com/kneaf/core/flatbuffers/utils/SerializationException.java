package com.kneaf.core.flatbuffers.utils;

import com.kneaf.core.exceptions.KneafCoreException;

/**
 * Exception thrown when serialization or deserialization operations fail. This exception provides
 * detailed context about the serialization failure.
 */
public class SerializationException extends KneafCoreException {

  private static final long serialVersionUID = 1L;

  private final String serializerType;
  private final String operation;
  private final byte[] data;

  /**
   * Create a new serialization exception.
   *
   * @param message the error message
   */
  public SerializationException(String message) {
    super(KneafCoreException.ErrorCategory.SYSTEM_ERROR, message);
    this.serializerType = "unknown";
    this.operation = "unknown";
    this.data = null;
  }

  /**
   * Create a new serialization exception with context.
   *
   * @param message the error message
   * @param serializerType the type of serializer that failed
   * @param operation the operation that failed (serialize/deserialize)
   */
  public SerializationException(String message, String serializerType, String operation) {
    super(KneafCoreException.ErrorCategory.SYSTEM_ERROR, message);
    this.serializerType = serializerType;
    this.operation = operation;
    this.data = null;
  }

  /**
   * Create a new serialization exception with context and data.
   *
   * @param message the error message
   * @param serializerType the type of serializer that failed
   * @param operation the operation that failed (serialize/deserialize)
   * @param data the data that caused the failure
   */
  public SerializationException(
      String message, String serializerType, String operation, byte[] data) {
    super(KneafCoreException.ErrorCategory.SYSTEM_ERROR, message);
    this.serializerType = serializerType;
    this.operation = operation;
    this.data = data != null ? data.clone() : null;
  }

  /**
   * Create a new serialization exception with cause.
   *
   * @param message the error message
   * @param cause the underlying cause
   */
  public SerializationException(String message, Throwable cause) {
    super(KneafCoreException.ErrorCategory.SYSTEM_ERROR, message, cause);
    this.serializerType = "unknown";
    this.operation = "unknown";
    this.data = null;
  }

  /**
   * Create a new serialization exception with full context.
   *
   * @param message the error message
   * @param cause the underlying cause
   * @param serializerType the type of serializer that failed
   * @param operation the operation that failed (serialize/deserialize)
   * @param data the data that caused the failure
   */
  public SerializationException(
      String message, Throwable cause, String serializerType, String operation, byte[] data) {
    super(KneafCoreException.ErrorCategory.SYSTEM_ERROR, message, cause);
    this.serializerType = serializerType;
    this.operation = operation;
    this.data = data != null ? data.clone() : null;
  }

  /**
   * Get the serializer type that failed.
   *
   * @return serializer type string
   */
  public String getSerializerType() {
    return serializerType;
  }

  /**
   * Get the operation that failed.
   *
   * @return operation string (serialize/deserialize)
   */
  public String getOperation() {
    return operation;
  }

  /**
   * Get the data that caused the failure.
   *
   * @return data byte array (may be null)
   */
  public byte[] getData() {
    return data != null ? data.clone() : null;
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("SerializationException{");
    sb.append("message='").append(getMessage()).append('\'');
    sb.append(", serializerType='").append(serializerType).append('\'');
    sb.append(", operation='").append(operation).append('\'');
    if (data != null) {
      sb.append(", dataLength=").append(data.length);
    }
    if (getCause() != null) {
      sb.append(", cause=").append(getCause().getClass().getSimpleName());
    }
    sb.append('}');
    return sb.toString();
  }
}
