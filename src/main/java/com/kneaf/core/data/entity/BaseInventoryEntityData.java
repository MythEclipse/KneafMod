package com.kneaf.core.data.entity;

import com.kneaf.core.data.core.DataValidationException;
import java.util.List;

/**
 * Base class for entity data with inventory capabilities.
 */
public abstract class BaseInventoryEntityData extends BaseEntityData {
    
    protected final List<String> inventoryItems;
    protected final int inventorySize;
    protected final boolean hasInventorySpace;
    
    /**
     * Creates a new base inventory entity data instance.
     * @param id the entity ID
     * @param x the X coordinate
     * @param y the Y coordinate
     * @param z the Z coordinate
     * @param entityType the entity type
     * @param inventoryItems the list of items in inventory
     * @param inventorySize the size of the inventory
     * @param hasInventorySpace whether there is inventory space available
     */
    protected BaseInventoryEntityData(long id, double x, double y, double z, String entityType,
                                     List<String> inventoryItems, int inventorySize, boolean hasInventorySpace) {
        super(id, x, y, z, entityType);
        this.inventoryItems = inventoryItems != null ? List.copyOf(inventoryItems) : List.of();
        this.inventorySize = inventorySize;
        this.hasInventorySpace = hasInventorySpace;
    }
    
    public List<String> getInventoryItems() {
        return inventoryItems;
    }
    
    public int getInventorySize() {
        return inventorySize;
    }
    
    public boolean hasInventorySpace() {
        return hasInventorySpace;
    }
    
    /**
     * Gets the number of items in the inventory.
     * @return the number of items
     */
    public int getItemCount() {
        return inventoryItems.size();
    }
    
    /**
     * Checks if the inventory is empty.
     * @return true if inventory has no items
     */
    public boolean isInventoryEmpty() {
        return inventoryItems.isEmpty();
    }
    
    /**
     * Checks if the inventory is full.
     * @return true if inventory has no space
     */
    public boolean isInventoryFull() {
        return !hasInventorySpace;
    }
    
    /**
     * Gets the inventory utilization percentage.
     * @return utilization percentage between 0.0 and 1.0
     */
    public double getInventoryUtilization() {
        return inventorySize > 0 ? (double) inventoryItems.size() / inventorySize : 0.0;
    }
    
    @Override
    public boolean validate() {
        super.validate();
        
        if (inventorySize < 0) {
            throw new DataValidationException("inventorySize", inventorySize, "Inventory size must not be negative");
        }
        
        if (inventoryItems.size() > inventorySize) {
            throw new DataValidationException("inventoryItems", inventoryItems.size(), 
                String.format("Item count (%d) cannot exceed inventory size (%d)", inventoryItems.size(), inventorySize));
        }
        
        return true;
    }
}