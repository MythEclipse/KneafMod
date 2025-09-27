package com.kneaf.core;

public class EntityData {
    public long id;
    public double distance;
    public boolean isBlockEntity;
    public String entityType;

    public EntityData(long id, double distance, boolean isBlockEntity, String entityType) {
        this.id = id;
        this.distance = distance;
        this.isBlockEntity = isBlockEntity;
        this.entityType = entityType;
    }
}