package com.kneaf.core;

public class EntityData {
    private long id;
    private double distance;
    private boolean isBlockEntity;
    private String entityType;

    public EntityData(long id, double distance, boolean isBlockEntity, String entityType) {
        this.id = id;
        this.distance = distance;
        this.isBlockEntity = isBlockEntity;
        this.entityType = entityType;
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public double getDistance() {
        return distance;
    }

    public void setDistance(double distance) {
        this.distance = distance;
    }

    public boolean isBlockEntity() {
        return isBlockEntity;
    }

    public void setBlockEntity(boolean blockEntity) {
        isBlockEntity = blockEntity;
    }

    public String getEntityType() {
        return entityType;
    }

    public void setEntityType(String entityType) {
        this.entityType = entityType;
    }
}