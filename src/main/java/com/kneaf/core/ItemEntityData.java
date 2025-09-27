package com.kneaf.core;

public class ItemEntityData {
    public int id;
    public int chunkX;
    public int chunkZ;
    public String itemType;
    public int count;
    public long ageSeconds;

    public ItemEntityData(int id, int chunkX, int chunkZ, String itemType, int count, long ageSeconds) {
        this.id = id;
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.itemType = itemType;
        this.count = count;
        this.ageSeconds = ageSeconds;
    }
}