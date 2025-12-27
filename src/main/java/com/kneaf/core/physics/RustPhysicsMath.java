package com.kneaf.core.physics;

import com.kneaf.core.RustNativeLoader;

/**
 * Interface for Rust-accelerated physics calculations.
 * Offloads pure math operations to native code for performance.
 */
public class RustPhysicsMath {

    /**
     * Apply friction to velocity vector.
     * 
     * @param vx       X velocity
     * @param vy       Y velocity
     * @param vz       Z velocity
     * @param friction Friction coefficient
     * @return double[] {newVx, newVy, newVz}
     */
    public static double[] applyFriction(double vx, double vy, double vz, float friction) {
        if (!RustNativeLoader.isLoaded()) {
            return new double[] { vx * friction, vy * friction, vz * friction };
        }
        // Native call would go here - utilizing existing vector math form
        // RustNativeLoader
        // Re-using the vector multiply from RustNativeLoader as it's essentially the
        // same op
        try {
            return RustNativeLoader.rustperf_vector_damp(vx, vy, vz, friction);
        } catch (UnsatisfiedLinkError e) {
            return new double[] { vx * friction, vy * friction, vz * friction };
        }
    }

    /**
     * Calculate gravity application.
     * 
     * @param velocityY Current vertical velocity
     * @param gravity   Gravity constant
     * @param slowFall  Is slow falling active
     * @return New vertical velocity
     */
    public static double applyGravity(double velocityY, double gravity, boolean slowFall) {
        // Simple enough for Java, but provided for completeness if we want to move more
        // logic later
        return slowFall ? velocityY : velocityY - gravity;
    }
}
