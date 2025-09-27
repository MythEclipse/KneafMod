package com.kneaf.core;

public class MobData {
    public long id;
    public double distance;
    public boolean isPassive;
    public String entityType;

    public MobData(long id, double distance, boolean isPassive, String entityType) {
        this.id = id;
        this.distance = distance;
        this.isPassive = isPassive;
        this.entityType = entityType;
    }
}