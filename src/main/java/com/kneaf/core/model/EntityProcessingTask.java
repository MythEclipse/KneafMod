package com.kneaf.core.model;

import com.kneaf.core.EntityInterface;

/**
 * Entity processing task
 */
public class EntityProcessingTask {
    public final EntityInterface entity;
    public final EntityPhysicsData physicsData;
    public final EntityPriority priority;

    public EntityProcessingTask(EntityInterface entity, EntityPhysicsData physicsData) {
        this(entity, physicsData, EntityPriority.MEDIUM);
    }

    public EntityProcessingTask(EntityInterface entity, EntityPhysicsData physicsData, EntityPriority priority) {
        this.entity = entity;
        this.physicsData = physicsData;
        this.priority = priority;
    }
}
