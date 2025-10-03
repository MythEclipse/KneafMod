package com.kneaf.core.performance.core;

import com.kneaf.core.data.entity.EntityData;
import com.kneaf.core.data.item.ItemEntityData;
import com.kneaf.core.data.entity.MobData;
import com.kneaf.core.data.block.BlockEntityData;
import com.kneaf.core.data.entity.PlayerData;
import com.kneaf.core.data.entity.VillagerData;
import java.util.List;

/**
 * Interface for performance processors that handle different types of game entities.
 */
public interface PerformanceProcessor {
    
    /**
     * Process entities and return list of entity IDs that should be ticked.
     */
    List<Long> processEntities(List<EntityData> entities, List<PlayerData> players);
    
    /**
     * Process item entities and return optimization results.
     */
    ItemProcessResult processItemEntities(List<ItemEntityData> items);
    
    /**
     * Process mob AI and return optimization results.
     */
    MobProcessResult processMobAI(List<MobData> mobs);
    
    /**
     * Process block entities and return list of block entity IDs that should be ticked.
     */
    List<Long> getBlockEntitiesToTick(List<BlockEntityData> blockEntities);
    
    /**
     * Process villager AI and return optimization results.
     */
    VillagerProcessResult processVillagerAI(List<VillagerData> villagers);
    
    /**
     * Result class for item processing operations.
     */
    class ItemProcessResult {
        private final List<Long> itemsToRemove;
        private final long mergedCount;
        private final long despawnedCount;
        private final List<ItemUpdate> itemUpdates;
        
        public ItemProcessResult(List<Long> itemsToRemove, long mergedCount, long despawnedCount, List<ItemUpdate> itemUpdates) {
            this.itemsToRemove = itemsToRemove;
            this.mergedCount = mergedCount;
            this.despawnedCount = despawnedCount;
            this.itemUpdates = itemUpdates;
        }
        
        public List<Long> getItemsToRemove() { return itemsToRemove; }
        public long getMergedCount() { return mergedCount; }
        public long getDespawnedCount() { return despawnedCount; }
        public List<ItemUpdate> getItemUpdates() { return itemUpdates; }
    }
    
    /**
     * Result class for item updates.
     */
    class ItemUpdate {
        private final long id;
        private final int newCount;
        
        public ItemUpdate(long id, int newCount) {
            this.id = id;
            this.newCount = newCount;
        }
        
        public long getId() { return id; }
        public int getNewCount() { return newCount; }
    }
    
    /**
     * Result class for mob processing operations.
     */
    class MobProcessResult {
        private final List<Long> mobsToDisableAI;
        private final List<Long> mobsToSimplifyAI;
        
        public MobProcessResult(List<Long> mobsToDisableAI, List<Long> mobsToSimplifyAI) {
            this.mobsToDisableAI = mobsToDisableAI;
            this.mobsToSimplifyAI = mobsToSimplifyAI;
        }
        
        public List<Long> getMobsToDisableAI() { return mobsToDisableAI; }
        public List<Long> getMobsToSimplifyAI() { return mobsToSimplifyAI; }
    }
    
    /**
     * Result class for villager processing operations.
     */
    class VillagerProcessResult {
        private final List<Long> disableList;
        private final List<Long> simplifyList;
        private final List<Long> reducePathfindList;
        
        public VillagerProcessResult(List<Long> disableList, List<Long> simplifyList, 
                                   List<Long> reducePathfindList) {
            this.disableList = disableList;
            this.simplifyList = simplifyList;
            this.reducePathfindList = reducePathfindList;
        }
        
        public List<Long> getDisableList() { return disableList; }
        public List<Long> getSimplifyList() { return simplifyList; }
        public List<Long> getReducePathfindList() { return reducePathfindList; }
    }
}