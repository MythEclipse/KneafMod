package com.kneaf.core;

public class MobData {
    public int id;
    public double distance;
    public boolean isPassive;
    public String entityType;

    public MobData(int id, double distance, boolean isPassive, String entityType) {
        this.id = id;
        this.distance = distance;
        this.isPassive = isPassive;
        this.entityType = entityType;
    }
}