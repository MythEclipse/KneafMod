package com.kneaf.core;

public class EntityData {
    public int id;
    public double distance;
    public boolean isBlockEntity;
    public String entityType;

    public EntityData(int id, double distance, boolean isBlockEntity, String entityType) {
        this.id = id;
        this.distance = distance;
        this.isBlockEntity = isBlockEntity;
        this.entityType = entityType;
    }
}