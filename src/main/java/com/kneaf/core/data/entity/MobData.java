package com.kneaf.core.data.entity;

import com.kneaf.core.data.core.DataConstants;
import com.kneaf.core.data.core.DataUtils;

/**
 * Data class representing a mob entity.
 */
public class MobData extends BaseEntityData {
    
    private final double distance;
    private final boolean isPassive;
    
    /**
     * Creates a new mob data instance.
     * @param id the mob ID
     * @param distance the distance from reference point
     * @param isPassive whether this is a passive mob
     * @param entityType the entity type
     * @throws com.kneaf.core.data.core.DataValidationException if validation fails
     */
    public MobData(long id, double distance, boolean isPassive, String entityType) {
        super(id, 0.0, 0.0, 0.0, entityType); // Mobs don't always have position data
        this.distance = distance;
        this.isPassive = isPassive;
    }
    
    public double getDistance() {
        return distance;
    }
    
    public boolean isPassive() {
        return isPassive;
    }
    
    @Override
    public boolean validate() {
        super.validate();
        DataUtils.validateDistance(distance, "distance");
        DataUtils.validateNotEmpty(entityType, "entityType");
        return true;
    }
    
    /**
     * Creates a builder for MobData.
     * @return a new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }
    
    /**
     * Builder class for MobData.
     */
    public static class Builder {
        private long id;
        private double distance = DataConstants.DEFAULT_DISTANCE;
        private boolean isPassive = true;
        private String entityType = "";
        
        public Builder id(long id) {
            this.id = id;
            return this;
        }
        
        public Builder distance(double distance) {
            this.distance = distance;
            return this;
        }
        
        public Builder isPassive(boolean isPassive) {
            this.isPassive = isPassive;
            return this;
        }
        
        public Builder entityType(String entityType) {
            this.entityType = entityType;
            return this;
        }
        
        public MobData build() {
            return new MobData(id, distance, isPassive, entityType);
        }
    }
}