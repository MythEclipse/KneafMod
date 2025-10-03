package com.kneaf.core.data.item;

import com.kneaf.core.data.core.DataConstants;
import com.kneaf.core.data.core.DataEntity;
import com.kneaf.core.data.core.DataUtils;
import com.kneaf.core.data.core.DataValidationException;

/**
 * Data class representing an item entity.
 */
public class ItemEntityData implements DataEntity {
    
    private final long id;
    private final int chunkX;
    private final int chunkZ;
    private final String itemType;
    private final int count;
    private final int ageSeconds;
    
    /**
     * Creates a new item entity data instance.
     * @param id the item entity ID
     * @param chunkX the chunk X coordinate
     * @param chunkZ the chunk Z coordinate
     * @param itemType the item type
     * @param count the item count
     * @param ageSeconds the age in seconds
     * @throws com.kneaf.core.data.core.DataValidationException if validation fails
     */
    public ItemEntityData(long id, int chunkX, int chunkZ, String itemType, int count, int ageSeconds) {
        this.id = id;
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.itemType = itemType;
        this.count = count;
        this.ageSeconds = ageSeconds;
        
        validate();
    }
    
    @Override
    public long getId() {
        return id;
    }
    
    @Override
    public String getType() {
        return DataConstants.ENTITY_TYPE_ITEM;
    }
    
    public int getChunkX() {
        return chunkX;
    }
    
    public int getChunkZ() {
        return chunkZ;
    }
    
    public String getItemType() {
        return itemType;
    }
    
    public int getCount() {
        return count;
    }
    
    public int getAgeSeconds() {
        return ageSeconds;
    }
    
    @Override
    public boolean validate() {
        DataUtils.validateNonNegative(id, "id");
        DataUtils.validateChunkCoordinate(chunkX, "chunkX");
        DataUtils.validateChunkCoordinate(chunkZ, "chunkZ");
        DataUtils.validateNotEmpty(itemType, "itemType");
        DataUtils.validateItemCount(count, "count");
        DataUtils.validateItemAge(ageSeconds, "ageSeconds");
        return true;
    }
    
    /**
     * Checks if the item is old enough to despawn.
     * @return true if the item should despawn
     */
    public boolean shouldDespawn() {
        return ageSeconds >= DataConstants.MAX_ITEM_AGE;
    }
    
    /**
     * Checks if the item can be merged with another item.
     * @param other the other item to check
     * @return true if the items can be merged
     */
    public boolean canMergeWith(ItemEntityData other) {
        if (other == null) return false;
        return itemType.equals(other.itemType) && 
               chunkX == other.chunkX && 
               chunkZ == other.chunkZ &&
               count + other.count <= DataConstants.MAX_ITEM_COUNT;
    }
    
    /**
     * Merges this item with another item.
     * @param other the other item to merge with
     * @return a new ItemEntityData with combined count
     */
    public ItemEntityData mergeWith(ItemEntityData other) {
        if (!canMergeWith(other)) {
            throw new IllegalArgumentException("Items cannot be merged");
        }
        return new ItemEntityData(id, chunkX, chunkZ, itemType, count + other.count, ageSeconds);
    }
    
    /**
     * Creates a builder for ItemEntityData.
     * @return a new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }
    
    /**
     * Builder class for ItemEntityData.
     */
    public static class Builder {
        private long id;
        private int chunkX;
        private int chunkZ;
        private String itemType = "";
        private int count = DataConstants.MIN_ITEM_COUNT;
        private int ageSeconds = DataConstants.MIN_ITEM_AGE;
        
        public Builder id(long id) {
            this.id = id;
            return this;
        }
        
        public Builder chunkPosition(int chunkX, int chunkZ) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            return this;
        }
        
        public Builder itemType(String itemType) {
            this.itemType = itemType;
            return this;
        }
        
        public Builder count(int count) {
            this.count = count;
            return this;
        }
        
        public Builder ageSeconds(int ageSeconds) {
            this.ageSeconds = ageSeconds;
            return this;
        }
        
        public ItemEntityData build() {
            return new ItemEntityData(id, chunkX, chunkZ, itemType, count, ageSeconds);
        }
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        
        ItemEntityData that = (ItemEntityData) obj;
        return id == that.id;
    }
    
    @Override
    public int hashCode() {
        return Long.hashCode(id);
    }
    
    @Override
    public String toString() {
        return String.format("ItemEntityData{id=%d, chunk=[%d,%d], type='%s', count=%d, age=%ds}", 
            id, chunkX, chunkZ, itemType, count, ageSeconds);
    }
}