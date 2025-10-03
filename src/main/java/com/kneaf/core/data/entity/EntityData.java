package com.kneaf.core.data.entity;

import com.kneaf.core.data.core.DataConstants;
import com.kneaf.core.data.core.DataUtils;

/**
 * Data class representing a general entity with position and type information.
 */
public class EntityData extends BaseEntityData {
    
    private final double distance;
    private final boolean isBlockEntity;
    
    /**
     * Creates a new entity data instance.
     * @param id the entity ID
     * @param x the X coordinate
     * @param y the Y coordinate
     * @param z the Z coordinate
     * @param distance the distance from reference point
     * @param isBlockEntity whether this is a block entity
     * @param entityType the entity type
     * @throws com.kneaf.core.data.core.DataValidationException if validation fails
     */
    public EntityData(long id, double x, double y, double z, double distance, 
                     boolean isBlockEntity, String entityType) {
        super(id, x, y, z, entityType);
        this.distance = distance;
        this.isBlockEntity = isBlockEntity;
    }
    
    public double getDistance() {
        return distance;
    }
    
    public boolean isBlockEntity() {
        return isBlockEntity;
    }
    
    @Override
    public boolean validate() {
        super.validate();
        DataUtils.validateDistance(distance, "distance");
        DataUtils.validateNotEmpty(entityType, "entityType");
        return true;
    }
    
    /**
     * Creates a builder for EntityData.
     * @return a new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }
    
    /**
     * Builder class for EntityData.
     */
    public static class Builder {
        private long id;
        private double x;
        private double y;
        private double z;
        private double distance = DataConstants.DEFAULT_DISTANCE;
        private boolean isBlockEntity = false;
        private String entityType = "";
        
        public Builder id(long id) {
            this.id = id;
            return this;
        }
        
        public Builder position(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
            return this;
        }
        
        public Builder distance(double distance) {
            this.distance = distance;
            return this;
        }
        
        public Builder isBlockEntity(boolean isBlockEntity) {
            this.isBlockEntity = isBlockEntity;
            return this;
        }
        
        public Builder entityType(String entityType) {
            this.entityType = entityType;
            return this;
        }
        
        public EntityData build() {
            return new EntityData(id, x, y, z, distance, isBlockEntity, entityType);
        }
    }
}