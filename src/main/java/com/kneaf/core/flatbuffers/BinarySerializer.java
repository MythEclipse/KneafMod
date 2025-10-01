package com.kneaf.core.flatbuffers;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Generic binary serializer utility for FlatBuffers-style serialization.
 * Eliminates DRY violations by providing common serialization/deserialization patterns.
 */
public class BinarySerializer {
    
    private BinarySerializer() {}
    
    /**
     * Field type enum for type-safe serialization
     */
    public enum FieldType {
        LONG(8),
        INT(4),
        FLOAT(4),
        BYTE(1),
        UTF8_STRING(-1); // Variable length
        
        private final int size;
        
        FieldType(int size) {
            this.size = size;
        }
        
        public int getSize() {
            return size;
        }
    }
    
    /**
     * Field descriptor for serializing individual fields
     */
    public static class FieldDescriptor<T> {
        private final FieldType type;
        private final Function<T, Object> getter;
        private final BiConsumer<ByteBuffer, Object> serializer;
        private final Function<ByteBuffer, Object> deserializer;
        private final int fixedSize;
        
        private FieldDescriptor(FieldType type, Function<T, Object> getter,
                              BiConsumer<ByteBuffer, Object> serializer,
                              Function<ByteBuffer, Object> deserializer) {
            this.type = type;
            this.getter = getter;
            this.serializer = serializer;
            this.deserializer = deserializer;
            this.fixedSize = type.getSize();
        }
        
        @SuppressWarnings("unchecked")
        private static <T, R> FieldDescriptor<T> createFieldDescriptor(FieldType type, Function<T, R> getter,
                                                                       BiConsumer<ByteBuffer, R> serializer,
                                                                       Function<ByteBuffer, R> deserializer) {
            return new FieldDescriptor<T>(type,
                (Function<T, Object>) (Function<?, ?>) getter,
                (BiConsumer<ByteBuffer, Object>) (BiConsumer<?, ?>) serializer,
                (Function<ByteBuffer, Object>) (Function<?, ?>) deserializer);
        }
        
        public static <T> FieldDescriptor<T> longField(Function<T, Long> getter) {
            return createFieldDescriptor(FieldType.LONG, getter,
                (buf, val) -> buf.putLong(val),
                ByteBuffer::getLong);
        }
        
        public static <T> FieldDescriptor<T> intField(Function<T, Integer> getter) {
            return createFieldDescriptor(FieldType.INT, getter,
                (buf, val) -> buf.putInt(val),
                ByteBuffer::getInt);
        }
        
        public static <T> FieldDescriptor<T> floatField(Function<T, Float> getter) {
            return createFieldDescriptor(FieldType.FLOAT, getter,
                (buf, val) -> buf.putFloat(val),
                ByteBuffer::getFloat);
        }
        
        public static <T> FieldDescriptor<T> byteField(Function<T, Byte> getter) {
            return createFieldDescriptor(FieldType.BYTE, getter,
                (buf, val) -> buf.put(val),
                ByteBuffer::get);
        }
        
        public static <T> FieldDescriptor<T> utf8StringField(Function<T, String> getter) {
            return createFieldDescriptor(FieldType.UTF8_STRING, getter,
                (buf, val) -> {
                    String str = (String) val;
                    byte[] bytes = str != null ? str.getBytes(StandardCharsets.UTF_8) : new byte[0];
                    buf.putInt(bytes.length);
                    if (bytes.length > 0) buf.put(bytes);
                },
                buf -> {
                    int len = buf.getInt();
                    // Sanity checks to avoid allocating huge arrays from malformed input
                    if (len < 0) {
                        System.err.println("[BinarySerializer] Invalid negative string length: " + len + ", returning empty string");
                        return "";
                    }
                    final int MAX_STRING_BYTES = 1_000_000; // 1MB cap for a single string
                    if (len > MAX_STRING_BYTES) {
                        System.err.println("[BinarySerializer] String length " + len + " exceeds max allowed " + MAX_STRING_BYTES + ", returning empty string");
                        // Advance the buffer position to skip the claimed bytes if possible
                        if (buf.remaining() >= len) {
                            buf.position(buf.position() + len);
                        } else {
                            // If not enough data, move to the end to avoid misreads later
                            buf.position(buf.limit());
                        }
                        return "";
                    }
                    if (buf.remaining() < len) {
                        System.err.println("[BinarySerializer] Buffer does not contain " + len + " bytes for string; remaining=" + buf.remaining() + ", returning empty string");
                        // Move to end to avoid repeated failures
                        buf.position(buf.limit());
                        return "";
                    }
                    if (len > 0) {
                        byte[] bytes = new byte[len];
                        buf.get(bytes);
                        return new String(bytes, StandardCharsets.UTF_8);
                    }
                    return "";
                });
        }
    }
    
    /**
     * Configuration for serialization parameters
     */
    public static class SerializationConfig<T> {
        private final int baseSize;
        private final List<FieldDescriptor<T>> fieldDescriptors;
        private final List<Float> configFloats;
        private final List<Integer> configInts;
        
        public SerializationConfig(int baseSize, List<FieldDescriptor<T>> fieldDescriptors, 
                                  List<Float> configFloats, List<Integer> configInts) {
            this.baseSize = baseSize;
            this.fieldDescriptors = fieldDescriptors;
            this.configFloats = configFloats != null ? configFloats : List.of();
            this.configInts = configInts != null ? configInts : List.of();
        }
        
        public int calculateSize(int itemCount) {
            int size = baseSize;
            for (FieldDescriptor<T> descriptor : fieldDescriptors) {
                if (descriptor.fixedSize > 0) {
                    size += descriptor.fixedSize * itemCount;
                }
            }
            // Add config sizes
            size += this.configFloats.size() * 4;
            size += this.configInts.size() * 4;
            return size;
        }
    }
    
    /**
     * Generic serialization method for lists of data
     */
    public static <T> ByteBuffer serializeList(long tickCount, List<T> items, 
                                               SerializationConfig<T> config, 
                                               Consumer<ByteBuffer> additionalSerializer) {
        // Calculate size
        int size = config.calculateSize(items.size());
        
        // Allocate buffer
        ByteBuffer buf = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        
        // Write header
        buf.putLong(tickCount);
        buf.putInt(items.size());
        
        // Write items
        for (T item : items) {
            for (FieldDescriptor<T> descriptor : config.fieldDescriptors) {
                Object value = descriptor.getter.apply(item);
                descriptor.serializer.accept(buf, value);
            }
        }
        
        // Apply additional serialization if provided
        if (additionalSerializer != null) {
            additionalSerializer.accept(buf);
        }
        
        // Write config values
        for (Float f : config.configFloats) {
            buf.putFloat(f);
        }
        for (Integer i : config.configInts) {
            buf.putInt(i);
        }
        
        buf.flip();
        return buf;
    }
    
    /**
     * Functional interface for deserializing objects from field values
     */
    @FunctionalInterface
    public interface FieldDeserializer<T> {
        T deserialize(Object[] fieldValues);
    }
    
    /**
     * Generic deserialization method for lists of data with field values
     */
    public static <T> List<T> deserializeList(ByteBuffer buffer,
                                              FieldDeserializer<T> deserializer,
                                              List<FieldDescriptor<T>> fieldDescriptors) {
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        buffer.rewind();
        
        int numItems = buffer.getInt();
        List<T> result = new ArrayList<>(numItems);
        
        for (int i = 0; i < numItems; i++) {
            Object[] values = new Object[fieldDescriptors.size()];
            for (int j = 0; j < fieldDescriptors.size(); j++) {
                values[j] = fieldDescriptors.get(j).deserializer.apply(buffer);
            }
            result.add(deserializer.deserialize(values));
        }
        
        return result;
    }
    
    /**
     * Simplified serialization for single list with field descriptors
     */
    public static <T> ByteBuffer serializeSimpleList(long tickCount, List<T> items,
                                                    int baseSize,
                                                    List<FieldDescriptor<T>> fieldDescriptors,
                                                    List<Float> configFloats,
                                                    List<Integer> configInts) {
        SerializationConfig<T> config = new SerializationConfig<>(baseSize, fieldDescriptors, configFloats, configInts);
        return serializeList(tickCount, items, config, null);
    }
    
    /**
     * Simplified deserialization for single list with field descriptors using functional interface
     */
    public static <T> List<T> deserializeSimpleList(ByteBuffer buffer,
                                                   FieldDeserializer<T> deserializer,
                                                   List<FieldDescriptor<T>> fieldDescriptors) {
        return deserializeList(buffer, deserializer, fieldDescriptors);
    }
}
