package com.kneaf.core.model;

import java.util.List;

/**
 * Entity batch for efficient bulk processing
 */
public class EntityBatch {
    private final List<EntityProcessingTask> tasks;
    private final EntityPriority priority;

    public EntityBatch(List<EntityProcessingTask> tasks, EntityPriority priority) {
        this.tasks = tasks;
        this.priority = priority;
    }

    public List<EntityProcessingTask> getTasks() {
        return tasks;
    }

    public EntityPriority getPriority() {
        return priority;
    }
}
