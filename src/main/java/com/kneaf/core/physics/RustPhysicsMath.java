/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 *
 * Rust-accelerated physics math operations for entity movement optimization.
 */
package com.kneaf.core.physics;

import com.kneaf.core.RustNativeLoader;

/**
 * Interface for Rust-accelerated physics calculations.
 * Offloads pure math operations (friction, gravity) to native code for
 * performance.
 *
 * <p>
 * All methods in this class are <b>thread-safe</b> and stateless. They can be
 * called concurrently from multiple entity processing threads without
 * synchronization.
 * </p>
 *
 * <p>
 * Each method provides a Java fallback implementation that is used
 * automatically
 * when the native Rust library is unavailable or fails to load.
 * </p>
 *
 * @author MYTHECLIPSE
 * @version 1.0.0
 * @since 1.21
 * @see RustNativeLoader
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
