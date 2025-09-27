package com.kneaf.core;

public class BlockEntityData {
    private long id;
    private double distance;
    private String blockType;
    private int x;
    private int y;
    private int z;

    public BlockEntityData(long id, double distance, String blockType, int x, int y, int z) {
        this.id = id;
        this.distance = distance;
        this.blockType = blockType;
        this.x = x;
        this.y = y;
        this.z = z;
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

    public String getBlockType() {
        return blockType;
    }

    public void setBlockType(String blockType) {
        this.blockType = blockType;
    }

    public int getX() {
        return x;
    }

    public void setX(int x) {
        this.x = x;
    }

    public int getY() {
        return y;
    }

    public void setY(int y) {
        this.y = y;
    }

    public int getZ() {
        return z;
    }

    public void setZ(int z) {
        this.z = z;
    }
}