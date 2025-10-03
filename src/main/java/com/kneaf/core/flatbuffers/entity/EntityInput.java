package com.kneaf.core.flatbuffers.entity;

import com.kneaf.core.data.entity.EntityData;
import com.kneaf.core.data.entity.PlayerData;

import java.util.List;

/**
 * Helper class for entity input data.
 */
public class EntityInput {
    public final long tickCount;
    public final List<EntityData> entities;
    public final List<PlayerData> players;
    
    public EntityInput(long tickCount, List<EntityData> entities, List<PlayerData> players) {
        this.tickCount = tickCount;
        this.entities = entities;
        this.players = players;
    }
}