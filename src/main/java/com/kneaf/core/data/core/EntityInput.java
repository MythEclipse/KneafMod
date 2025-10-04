package com.kneaf.core.data.core;

import com.kneaf.core.data.block.BlockEntityData;
import com.kneaf.core.data.entity.EntityData;
import com.kneaf.core.data.entity.MobData;
import com.kneaf.core.data.entity.PlayerData;
import com.kneaf.core.data.entity.VillagerData;
import com.kneaf.core.data.item.ItemEntityData;
import java.util.List;

/** Container class for entity data collections used in batch processing. */
public class EntityInput {

  private final List<EntityData> entities;
  private final List<PlayerData> players;
  private final List<MobData> mobs;
  private final List<VillagerData> villagers;
  private final List<ItemEntityData> items;
  private final List<BlockEntityData> blockEntities;

  public EntityInput(
      List<EntityData> entities,
      List<PlayerData> players,
      List<MobData> mobs,
      List<VillagerData> villagers,
      List<ItemEntityData> items,
      List<BlockEntityData> blockEntities) {
    this.entities = entities != null ? List.copyOf(entities) : List.of();
    this.players = players != null ? List.copyOf(players) : List.of();
    this.mobs = mobs != null ? List.copyOf(mobs) : List.of();
    this.villagers = villagers != null ? List.copyOf(villagers) : List.of();
    this.items = items != null ? List.copyOf(items) : List.of();
    this.blockEntities = blockEntities != null ? List.copyOf(blockEntities) : List.of();
  }

  public List<EntityData> entities() {
    return entities;
  }

  public List<PlayerData> players() {
    return players;
  }

  public List<MobData> mobs() {
    return mobs;
  }

  public List<VillagerData> villagers() {
    return villagers;
  }

  public List<ItemEntityData> items() {
    return items;
  }

  public List<BlockEntityData> blockEntities() {
    return blockEntities;
  }

  /**
   * Validates all contained entity data.
   *
   * @return true if all data is valid
   * @throws DataValidationException if any data is invalid
   */
  public boolean validate() {
    for (EntityData entity : entities) {
      entity.validate();
    }
    for (PlayerData player : players) {
      player.validate();
    }
    for (MobData mob : mobs) {
      mob.validate();
    }
    for (VillagerData villager : villagers) {
      villager.validate();
    }
    for (ItemEntityData item : items) {
      item.validate();
    }
    for (BlockEntityData blockEntity : blockEntities) {
      blockEntity.validate();
    }
    return true;
  }

  /**
   * Gets the total number of entities across all categories.
   *
   * @return the total entity count
   */
  public int getTotalEntityCount() {
    return entities.size()
        + players.size()
        + mobs.size()
        + villagers.size()
        + items.size()
        + blockEntities.size();
  }

  /**
   * Checks if this input contains any entities.
   *
   * @return true if any category has entities
   */
  public boolean hasEntities() {
    return !entities.isEmpty()
        || !players.isEmpty()
        || !mobs.isEmpty()
        || !villagers.isEmpty()
        || !items.isEmpty()
        || !blockEntities.isEmpty();
  }
}
