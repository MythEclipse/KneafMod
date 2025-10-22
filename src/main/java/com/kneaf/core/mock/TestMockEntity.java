package com.kneaf.core.mock;

import com.kneaf.core.EntityInterface;
import java.util.UUID;

/**
 * Comprehensive test mock entity that provides all necessary Minecraft Entity methods
 * for testing without actual Minecraft dependencies.
 */
public class TestMockEntity implements EntityInterface {
    private final int id;
    private final String type;
    private final UUID uuid;
    private double x, y, z;
    private float yaw, pitch;
    private final Vec3 deltaMovement = new Vec3(0, 0, 0);
    
    public TestMockEntity(int id, String type) {
        this.id = id;
        this.type = type;
        this.uuid = UUID.randomUUID();
        this.x = 0;
        this.y = 0;
        this.z = 0;
        this.yaw = 0;
        this.pitch = 0;
    }
    
    public long getId() {
        return id;
    }
    
    public UUID getUUID() {
        return uuid;
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
    
    public Vec3 getDeltaMovement() {
        return deltaMovement;
    }
    
    public void setDeltaMovement(double x, double y, double z) {
        this.deltaMovement.x = x;
        this.deltaMovement.y = y;
        this.deltaMovement.z = z;
    }
    
    public boolean hasCustomName() {
        return false;
    }
    
    public boolean isClientSide() {
        return false;
    }
    
    public boolean level() {
        return false;
    }
    
    public boolean isPlayer() {
        return false;
    }
    
    @Override
    public String toString() {
        return "TestMockEntity{id=" + id + ", type='" + type + "', x=" + x + ", y=" + y + ", z=" + z + "}";
    }
    
    public static class Vec3 {
        public double x, y, z;
        
        public Vec3(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }
}