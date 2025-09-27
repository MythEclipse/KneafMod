package com.kneaf.core;

public class BlockEntityData {
    public long id;
    public double distance;
    public String blockType;
    public int x;
    public int y;
    public int z;

    public BlockEntityData(long id, double distance, String blockType, int x, int y, int z) {
        this.id = id;
        this.distance = distance;
        this.blockType = blockType;
        this.x = x;
        this.y = y;
        this.z = z;
    }
}