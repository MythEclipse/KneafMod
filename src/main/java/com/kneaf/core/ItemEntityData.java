package com.kneaf.core;

public class ItemEntityData {
    private long id;
    private int chunkX;
    private int chunkZ;
    private String itemType;
    private int count;
    private int ageSeconds;

    public ItemEntityData(long id, int chunkX, int chunkZ, String itemType, int count, int ageSeconds) {
        this.id = id;
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.itemType = itemType;
        this.count = count;
        this.ageSeconds = ageSeconds;
    }

    public long getId() {
        return id;
    }

    public int getChunkX() {
        return chunkX;
    }

    public int getChunkZ() {
        return chunkZ;
    }

    public String getItemType() {
        return itemType;
    }

    public int getCount() {
        return count;
    }

    public int getAgeSeconds() {
        return ageSeconds;
    }
}