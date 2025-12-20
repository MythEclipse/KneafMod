package com.kneaf.core.model;

/**
 * Entity physics data container
 */
public class EntityPhysicsData {
    public double motionX;
    public double motionY;
    public double motionZ;

    public EntityPhysicsData(double motionX, double motionY, double motionZ) {
        this.motionX = motionX;
        this.motionY = motionY;
        this.motionZ = motionZ;
    }

    public void reset() {
        this.motionX = 0.0;
        this.motionY = 0.0;
        this.motionZ = 0.0;
    }
}
