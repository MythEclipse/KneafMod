package com.kneaf.core.flatbuffers.item;

import com.kneaf.core.data.item.ItemEntityData;
import com.kneaf.core.flatbuffers.core.BaseFlatBufferSerializer;
import com.kneaf.core.flatbuffers.core.SerializationConstants;
import com.kneaf.core.flatbuffers.core.SerializationUtils;
import com.kneaf.core.flatbuffers.utils.BufferPool;
import com.kneaf.core.flatbuffers.utils.SerializationException;
import com.kneaf.core.flatbuffers.utils.SchemaValidator;
import com.kneaf.core.performance.core.ItemProcessResult;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Serializer for item entity data using flatbuffer format.
 * Handles serialization and deserialization of item processing results.
 */
public class ItemSerializer extends BaseFlatBufferSerializer<ItemInput, ItemProcessResult> {
    
    /**
     * Create a new item serializer with default configuration.
     */
    public ItemSerializer() {
        super(SerializationConstants.ENTITY_TYPE_ITEM, SerializationConstants.SCHEMA_VERSION_1_0);
    }
    
    /**
     * Create a new item serializer with custom buffer pool.
     * 
     * @param bufferPool the buffer pool to use
     */
    public ItemSerializer(BufferPool bufferPool) {
        super(SerializationConstants.ENTITY_TYPE_ITEM, SerializationConstants.SCHEMA_VERSION_1_0, bufferPool);
    }
    
    /**
     * Create a new item serializer with full configuration.
     * 
     * @param bufferPool the buffer pool to use
     * @param validator the schema validator to use
     */
    public ItemSerializer(BufferPool bufferPool, SchemaValidator<ItemInput> validator) {
        super(SerializationConstants.ENTITY_TYPE_ITEM, SerializationConstants.SCHEMA_VERSION_1_0, bufferPool, validator);
    }
    
    @Override
    protected void serializeToBufferInternal(ItemInput input, ByteBuffer buffer) throws SerializationException {
        if (input == null) {
            throw new SerializationException("Item input cannot be null", getSerializerType(), "serializeToBufferInternal");
        }
        
        try {
            // Write tick count (8 bytes)
            buffer.putLong(input.tickCount);
            
            // Write item count (4 bytes)
            List<ItemEntityData> items = input.items;
            buffer.putInt(items.size());
            
            // Write item data
            for (ItemEntityData item : items) {
                writeItemData(buffer, item);
            }
            
        } catch (Exception e) {
            throw new SerializationException("Failed to serialize item input", e, getSerializerType(), 
                                           "serializeToBufferInternal", buffer.array());
        }
    }
    
    @Override
    protected ItemProcessResult deserializeFromBufferInternal(ByteBuffer buffer) throws SerializationException {
        try {
            // Read tick count (8 bytes) - placeholder, not used in result
            long tickCount = buffer.getLong();
            
            // Read item count (4 bytes)
            int itemCount = buffer.getInt();
            if (itemCount < 0) {
                throw new SerializationException("Invalid item count: " + itemCount, getSerializerType(), 
                                               "deserializeFromBufferInternal");
            }
            
            // Read item data
            List<ItemEntityData> resultItems = new ArrayList<>(itemCount);
            for (int i = 0; i < itemCount; i++) {
                resultItems.add(readItemData(buffer));
            }
            
            // Extract items to remove (count == 0) and items to update (count > 0)
            List<Long> itemsToRemove = new ArrayList<>();
            List<com.kneaf.core.performance.core.PerformanceProcessor.ItemUpdate> itemUpdates = new ArrayList<>();
            
            for (ItemEntityData item : resultItems) {
                if (item.getCount() == 0) {
                    itemsToRemove.add(item.getId());
                } else {
                    itemUpdates.add(new com.kneaf.core.performance.core.PerformanceProcessor.ItemUpdate(
                        item.getId(), item.getCount()));
                }
            }
            
            // Read merged and despawned counts (8 bytes each)
            long mergedCount = buffer.getLong();
            long despawnedCount = buffer.getLong();
            
            return new ItemProcessResult(itemsToRemove, (int) mergedCount, (int) despawnedCount, itemUpdates);
            
        } catch (SerializationException e) {
            throw e;
        } catch (Exception e) {
            throw new SerializationException("Failed to deserialize item result", e, getSerializerType(), 
                                           "deserializeFromBufferInternal", null);
        }
    }
    
    /**
     * Write item data to buffer.
     * 
     * @param buffer the buffer to write to
     * @param item the item data
     */
    private void writeItemData(ByteBuffer buffer, ItemEntityData item) {
        buffer.putLong(item.getId());
        buffer.putInt(item.getChunkX());
        buffer.putInt(item.getChunkZ());
        SerializationUtils.writeString(buffer, item.getItemType());
        buffer.putInt(item.getCount());
        buffer.putInt(item.getAgeSeconds());
    }
    
    /**
     * Read item data from buffer.
     * 
     * @param buffer the buffer to read from
     * @return the item data
     */
    private ItemEntityData readItemData(ByteBuffer buffer) {
        long id = buffer.getLong();
        int chunkX = buffer.getInt();
        int chunkZ = buffer.getInt();
        String itemType = SerializationUtils.readString(buffer);
        int count = buffer.getInt();
        int ageSeconds = buffer.getInt();
        
        return new ItemEntityData(id, chunkX, chunkZ, itemType, count, ageSeconds);
    }
}