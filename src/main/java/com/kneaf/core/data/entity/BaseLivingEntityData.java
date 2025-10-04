package com.kneaf.core.data.entity;

import com.kneaf.core.data.core.DataValidationException;

/**
 * Base class for living entity data with health and attributes.
 */
public abstract class BaseLivingEntityData extends BaseEntityData {
    
    protected final double health;
    protected final double maxHealth;
    protected final boolean isAlive;
    
    /**
     * Creates a new base living entity data instance.
     * @param id the entity ID
     * @param x the X coordinate
     * @param y the Y coordinate
     * @param z the Z coordinate
     * @param entityType the entity type
     * @param health the current health
     * @param maxHealth the maximum health
     * @param isAlive whether the entity is alive
     * @throws com.kneaf.core.data.core.DataValidationException if validation fails
     */
    protected BaseLivingEntityData(long id, double x, double y, double z, String entityType, 
                                  double health, double maxHealth, boolean isAlive) {
        super(id, x, y, z, entityType);
        this.health = health;
        this.maxHealth = maxHealth;
        this.isAlive = isAlive;
    }
    
    public double getHealth() {
        return health;
    }
    
    public double getMaxHealth() {
        return maxHealth;
    }
    
    public boolean isAlive() {
        return isAlive;
    }
    
    @Override
    public boolean validate() {
        super.validate();
        
        if (health < 0) {
            throw new DataValidationException("health", health, "Health must not be negative");
        }
        
        if (maxHealth <= 0) {
            throw new DataValidationException("maxHealth", maxHealth, "Max health must be positive");
        }
        
        if (health > maxHealth) {
            throw new DataValidationException("health", health, 
                String.format("Health (%.2f) cannot exceed max health (%.2f)", health, maxHealth));
        }
        
        return true;
    }
    
    /**
     * Gets the health percentage.
     * @return health percentage between 0.0 and 1.0
     */
    public double getHealthPercentage() {
        return maxHealth > 0 ? health / maxHealth : 0.0;
    }
    
    /**
     * Checks if the entity is at full health.
     * @return true if health equals max health
     */
    public boolean isAtFullHealth() {
        return Double.compare(health, maxHealth) == 0;
    }
    
    /**
     * Checks if the entity is critically injured (health below 20% of max).
     * @return true if health is below 20% of max health
     */
    public boolean isCriticallyInjured() {
        return getHealthPercentage() < 0.2;
    }
}