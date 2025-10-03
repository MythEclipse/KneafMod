package com.kneaf.core.data;

/**
 * Data class representing a villager entity for performance optimization processing.
 */
public class VillagerData {
    private final long id;
    private final double x;
    private final double y;
    private final double z;
    private final double distance;
    private final String profession;
    private final int level;
    private final boolean hasWorkstation;
    private final boolean isResting;
    private final boolean isBreeding;
    private final long lastPathfindTick;
    private final int pathfindFrequency;
    private final int aiComplexity;

    public VillagerData(long id, double x, double y, double z, double distance, 
                       String profession, int level, boolean hasWorkstation, 
                       boolean isResting, boolean isBreeding, long lastPathfindTick,
                       int pathfindFrequency, int aiComplexity) {
        this.id = id;
        this.x = x;
        this.y = y;
        this.z = z;
        this.distance = distance;
        this.profession = profession;
        this.level = level;
        this.hasWorkstation = hasWorkstation;
        this.isResting = isResting;
        this.isBreeding = isBreeding;
        this.lastPathfindTick = lastPathfindTick;
        this.pathfindFrequency = pathfindFrequency;
        this.aiComplexity = aiComplexity;
    }

    public long getId() {
        return id;
    }

    public double getX() {
        return x;
    }

    public double getY() {
        return y;
    }

    public double getZ() {
        return z;
    }

    public double getDistance() {
        return distance;
    }

    public String getProfession() {
        return profession;
    }

    public int getLevel() {
        return level;
    }

    public boolean hasWorkstation() {
        return hasWorkstation;
    }

    public boolean isResting() {
        return isResting;
    }

    public boolean isBreeding() {
        return isBreeding;
    }

    public long getLastPathfindTick() {
        return lastPathfindTick;
    }

    public int getPathfindFrequency() {
        return pathfindFrequency;
    }

    public int getAiComplexity() {
        return aiComplexity;
    }
}