package com.kneaf.core.mock;

/**
 * Mock Entity class to replace Minecraft's Entity class for testing purposes.
 * This provides basic entity functionality without Minecraft dependencies.
 */
public class MockEntity {
    private final int id;
    private final String type;
    private double x, y, z;
    private float yaw, pitch;
    
    public MockEntity(int id, String type) {
        this.id = id;
        this.type = type;
        this.x = 0;
        this.y = 0;
        this.z = 0;
        this.yaw = 0;
        this.pitch = 0;
    }
    
    public int getId() {
        return id;
    }
    
    public String getType() {
        return type;
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
    
    public float getYaw() {
        return yaw;
    }
    
    public float getPitch() {
        return pitch;
    }
    
    public void setPosition(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }
    
    public void setRotation(float yaw, float pitch) {
        this.yaw = yaw;
        this.pitch = pitch;
    }
    
    @Override
    public String toString() {
        return "MockEntity{id=" + id + ", type='" + type + "', x=" + x + ", y=" + y + ", z=" + z + "}";
    }
}