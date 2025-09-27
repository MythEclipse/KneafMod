package com.kneaf.core;

public class ItemEntityData {
    public long id;
    public int chunkX;
    public int chunkZ;
    public String itemType;
    public int count;
    public int ageSeconds;

    public ItemEntityData(long id, int chunkX, int chunkZ, String itemType, int count, int ageSeconds) {
        this.id = id;
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.itemType = itemType;
        this.count = count;
        this.ageSeconds = ageSeconds;
    }
}