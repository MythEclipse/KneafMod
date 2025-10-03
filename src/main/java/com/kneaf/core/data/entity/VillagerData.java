package com.kneaf.core.data.entity;

import com.kneaf.core.data.core.DataConstants;
import com.kneaf.core.data.core.DataUtils;
import com.kneaf.core.data.core.DataValidationException;

/**
 * Data class representing a villager entity with profession and AI information.
 */
public class VillagerData extends BaseEntityData {
    
    private final double distance;
    private final String profession;
    private final int level;
    private final boolean hasWorkstation;
    private final boolean isResting;
    private final boolean isBreeding;
    private final long lastPathfindTick;
    private final int pathfindFrequency;
    private final int aiComplexity;
    
    /**
     * Creates a new villager data instance.
     * @param id the villager ID
     * @param x the X coordinate
     * @param y the Y coordinate
     * @param z the Z coordinate
     * @param distance the distance from reference point
     * @param profession the villager profession
     * @param level the villager level
     * @param hasWorkstation whether the villager has a workstation
     * @param isResting whether the villager is resting
     * @param isBreeding whether the villager is breeding
     * @param lastPathfindTick the last pathfinding tick
     * @param pathfindFrequency the pathfinding frequency
     * @param aiComplexity the AI complexity level
     * @throws com.kneaf.core.data.core.DataValidationException if validation fails
     */
    public VillagerData(long id, double x, double y, double z, double distance, 
                       String profession, int level, boolean hasWorkstation, 
                       boolean isResting, boolean isBreeding, long lastPathfindTick,
                       int pathfindFrequency, int aiComplexity) {
        super(id, x, y, z, DataConstants.ENTITY_TYPE_VILLAGER);
        this.distance = distance;
        // Be tolerant to null profession values coming from legacy callers/tests.
        // Use default profession rather than allowing a null to fail validation.
        this.profession = profession == null ? DataConstants.DEFAULT_PROFESSION : profession;
        this.level = level;
        this.hasWorkstation = hasWorkstation;
        this.isResting = isResting;
        this.isBreeding = isBreeding;
        this.lastPathfindTick = lastPathfindTick;
        this.pathfindFrequency = pathfindFrequency;
        this.aiComplexity = aiComplexity;
    }
    
    public double getDistance() {
        return distance;
    }
    
    public String getProfession() {
        return profession;
    }
    
    public int getLevel() {
        return level;
    }
    
    public boolean hasWorkstation() {
        return hasWorkstation;
    }
    
    public boolean isResting() {
        return isResting;
    }
    
    public boolean isBreeding() {
        return isBreeding;
    }
    
    public long getLastPathfindTick() {
        return lastPathfindTick;
    }
    
    public int getPathfindFrequency() {
        return pathfindFrequency;
    }
    
    public int getAiComplexity() {
        return aiComplexity;
    }
    
    @Override
    public boolean validate() {
        super.validate();
        DataUtils.validateDistance(distance, "distance");
        // Be tolerant: if profession somehow remains null (legacy callers), treat as default instead of failing
        if (profession != null) {
            DataUtils.validateNotEmpty(profession, "profession");
        }
        DataUtils.validateNonNegative(level, "level");
        DataUtils.validateNonNegative(lastPathfindTick, "lastPathfindTick");
        DataUtils.validateNonNegative(pathfindFrequency, "pathfindFrequency");
        DataUtils.validateNonNegative(aiComplexity, "aiComplexity");
        
        if (level > 5) {
            throw new DataValidationException("level", level, "Villager level cannot exceed 5");
        }
        
        return true;
    }
    
    /**
     * Checks if the villager can work (has workstation and is not resting).
     * @return true if the villager can work
     */
    public boolean canWork() {
        return hasWorkstation && !isResting;
    }
    
    /**
     * Checks if the villager is active (not resting and not breeding).
     * @return true if the villager is active
     */
    public boolean isActive() {
        return !isResting && !isBreeding;
    }
    
    /**
     * Gets the AI load score based on complexity and frequency.
     * @return the AI load score
     */
    public int getAiLoadScore() {
        return aiComplexity * pathfindFrequency;
    }
    
    /**
     * Creates a builder for VillagerData.
     * @return a new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }
    
    /**
     * Builder class for VillagerData.
     */
    public static class Builder {
        private long id;
        private double x;
        private double y;
        private double z;
        private double distance = DataConstants.DEFAULT_DISTANCE;
        private String profession = DataConstants.DEFAULT_PROFESSION;
        private int level = DataConstants.DEFAULT_LEVEL;
        private boolean hasWorkstation = DataConstants.DEFAULT_BOOLEAN;
        private boolean isResting = DataConstants.DEFAULT_BOOLEAN;
        private boolean isBreeding = DataConstants.DEFAULT_BOOLEAN;
        private long lastPathfindTick = DataConstants.DEFAULT_LAST_PATHFIND_TICK;
        private int pathfindFrequency = DataConstants.DEFAULT_PATHFIND_FREQUENCY;
        private int aiComplexity = DataConstants.DEFAULT_AI_COMPLEXITY;
        
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
        
        public Builder profession(String profession) {
            this.profession = profession;
            return this;
        }
        
        public Builder level(int level) {
            this.level = level;
            return this;
        }
        
        public Builder hasWorkstation(boolean hasWorkstation) {
            this.hasWorkstation = hasWorkstation;
            return this;
        }
        
        public Builder isResting(boolean isResting) {
            this.isResting = isResting;
            return this;
        }
        
        public Builder isBreeding(boolean isBreeding) {
            this.isBreeding = isBreeding;
            return this;
        }
        
        public Builder lastPathfindTick(long lastPathfindTick) {
            this.lastPathfindTick = lastPathfindTick;
            return this;
        }
        
        public Builder pathfindFrequency(int pathfindFrequency) {
            this.pathfindFrequency = pathfindFrequency;
            return this;
        }
        
        public Builder aiComplexity(int aiComplexity) {
            this.aiComplexity = aiComplexity;
            return this;
        }
        
        public VillagerData build() {
            return new VillagerData(id, x, y, z, distance, profession, level, hasWorkstation, 
                                   isResting, isBreeding, lastPathfindTick, pathfindFrequency, aiComplexity);
        }
    }
}