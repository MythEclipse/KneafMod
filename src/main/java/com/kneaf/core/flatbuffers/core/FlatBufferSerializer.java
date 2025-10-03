package com.kneaf.core.flatbuffers.core;

import java.nio.ByteBuffer;
import com.kneaf.core.flatbuffers.utils.SerializationException;

/**
 * Base interface for all flatbuffer serializers.
 * Defines the contract for serializing and deserializing data using flatbuffer format.
 */
public interface FlatBufferSerializer<T, R> {
    
    /**
     * Serialize input data to binary format.
     * 
     * @param input the input data to serialize
     * @return serialized binary data
     * @throws SerializationException if serialization fails
     */
    byte[] serialize(T input) throws SerializationException;
    
    /**
     * Deserialize binary data to output format.
     * 
     * @param data the binary data to deserialize
     * @return deserialized output data
     * @throws SerializationException if deserialization fails
     */
    R deserialize(byte[] data) throws SerializationException;
    
    /**
     * Serialize input data to ByteBuffer format.
     * 
     * @param input the input data to serialize
     * @return ByteBuffer containing serialized data
     * @throws SerializationException if serialization fails
     */
    ByteBuffer serializeToBuffer(T input) throws SerializationException;
    
    /**
     * Deserialize ByteBuffer data to output format.
     * 
     * @param buffer the ByteBuffer containing data to deserialize
     * @return deserialized output data
     * @throws SerializationException if deserialization fails
     */
    R deserializeFromBuffer(ByteBuffer buffer) throws SerializationException;
    
    /**
     * Get the schema version for this serializer.
     * 
     * @return schema version string
     */
    String getSchemaVersion();
    
    /**
     * Validate input data against schema.
     * 
     * @param input the input data to validate
     * @return true if valid, false otherwise
     */
    boolean validateInput(T input);
    
    /**
     * Get the serializer type identifier.
     * 
     * @return serializer type string
     */
    String getSerializerType();
}