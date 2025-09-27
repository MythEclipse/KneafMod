package com.kneaf.core;

public class MobData {
    private long id;
    private double distance;
    private boolean isPassive;
    private String entityType;

    public MobData(long id, double distance, boolean isPassive, String entityType) {
        this.id = id;
        this.distance = distance;
        this.isPassive = isPassive;
        this.entityType = entityType;
    }

    public long getId() {
        return id;
    }

    public double getDistance() {
        return distance;
    }

    public boolean isPassive() {
        return isPassive;
    }

    public String getEntityType() {
        return entityType;
    }
}