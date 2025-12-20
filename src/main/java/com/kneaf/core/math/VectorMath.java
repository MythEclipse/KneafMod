package com.kneaf.core.math;

import com.kneaf.core.RustNativeLoader;
import org.joml.Vector3f;
import java.util.stream.IntStream;

/**
 * Centralized Vector Math Utility.
 * 
 * Consolidates all vector and matrix operations previously scattered across the
 * codebase.
 * Automatically handles native acceleration via RustNativeLoader where
 * possible,
 * seamlessly falling back to high-performance Java implementations if native
 * libraries are unavailable.
 */
public class VectorMath {

    // Prevent instantiation
    private VectorMath() {
    }

    /**
     * Calculates the distance between two 3D points.
     */
    public static double distance(double x1, double y1, double z1, double x2, double y2, double z2) {
        if (RustNativeLoader.isLibraryLoaded()) {
            try {
                return RustNativeLoader.vectorDistance(x1, y1, z1, x2, y2, z2);
            } catch (UnsatisfiedLinkError ignored) {
                // Fallback
            }
        }
        return Math.sqrt(distanceSq(x1, y1, z1, x2, y2, z2));
    }

    public static double distanceSq(double x1, double y1, double z1, double x2, double y2, double z2) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        double dz = z2 - z1;
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * Calculates the length (magnitude) of a vector.
     */
    public static double length(double x, double y, double z) {
        if (RustNativeLoader.isLibraryLoaded()) {
            try {
                return RustNativeLoader.vectorLength(x, y, z);
            } catch (UnsatisfiedLinkError ignored) {
                // Fallback
            }
        }
        return Math.sqrt(x * x + y * y + z * z);
    }

    /**
     * Normalizes a vector. Returns a zero vector if the input is zero length.
     */
    public static double[] normalize(double x, double y, double z) {
        if (RustNativeLoader.isLibraryLoaded()) {
            try {
                return RustNativeLoader.vectorNormalize(x, y, z);
            } catch (UnsatisfiedLinkError ignored) {
                // Fallback
            }
        }

        double len = Math.sqrt(x * x + y * y + z * z);
        if (len == 0.0)
            return new double[] { 0.0, 0.0, 0.0 };
        return new double[] { x / len, y / len, z / len };
    }

    public static float[] normalize(float[] v) {
        if (v == null || v.length != 3)
            throw new IllegalArgumentException("Vector must be length 3");

        // Use double implementation locally since we don't have a direct float native
        // shim for single vector
        // Or implement pure Java fallback for floats
        double len = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        if (len == 0.0)
            return new float[] { 0f, 0f, 0f };
        return new float[] { (float) (v[0] / len), (float) (v[1] / len), (float) (v[2] / len) };
    }

    public static Vector3f normalize(Vector3f v) {
        if (v == null)
            throw new IllegalArgumentException("Vector cannot be null");
        double[] res = normalize(v.x(), v.y(), v.z());
        return new Vector3f((float) res[0], (float) res[1], (float) res[2]);
    }

    /**
     * Dot product of two vectors.
     */
    public static float dot(float[] a, float[] b) {
        if (a.length != 3 || b.length != 3)
            throw new IllegalArgumentException("Vectors must be length 3");

        // Native optimization check could go here if exposed
        if (RustNativeLoader.isLibraryLoaded()) {
            try {
                // This native method typically expected arrays, checking signature from
                // RustNativeLoader
                return RustNativeLoader.glam_vector_dot(a, b);
            } catch (UnsatisfiedLinkError ignored) {
            }
        }

        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    public static float dot(Vector3f a, Vector3f b) {
        return dot(new float[] { a.x, a.y, a.z }, new float[] { b.x, b.y, b.z });
    }

    /**
     * Cross product of two vectors.
     */
    public static float[] cross(float[] a, float[] b) {
        if (a.length != 3 || b.length != 3)
            throw new IllegalArgumentException("Vectors must be length 3");

        if (RustNativeLoader.isLibraryLoaded()) {
            try {
                return RustNativeLoader.glam_vector_cross(a, b);
            } catch (UnsatisfiedLinkError ignored) {
            }
        }

        return new float[] {
                a[1] * b[2] - a[2] * b[1],
                a[2] * b[0] - a[0] * b[2],
                a[0] * b[1] - a[1] * b[0]
        };
    }

    public static Vector3f cross(Vector3f a, Vector3f b) {
        float[] res = cross(new float[] { a.x, a.y, a.z }, new float[] { b.x, b.y, b.z });
        return new Vector3f(res[0], res[1], res[2]);
    }

    /**
     * Matrix multiplication (4x4).
     */
    public static float[] matrixMultiply(float[] a, float[] b) {
        if (a.length != 16 || b.length != 16)
            throw new IllegalArgumentException("Matrices must be 4x4 (length 16)");

        if (RustNativeLoader.isLibraryLoaded()) {
            try {
                // Prefer Glam or Nalgebra based on RustNativeLoader availability
                return RustNativeLoader.glam_matrix_mul(a, b);
            } catch (UnsatisfiedLinkError ignored) {
            }
        }

        // Java Fallback (Parallel for performance)
        float[] r = new float[16];
        IntStream.range(0, 4).parallel().forEach(row -> {
            for (int col = 0; col < 4; col++) {
                float sum = 0f;
                for (int k = 0; k < 4; k++) {
                    sum += a[row * 4 + k] * b[k * 4 + col];
                }
                r[row * 4 + col] = sum;
            }
        });
        return r;
    }

    /**
     * Vector Addition.
     */
    public static float[] add(float[] a, float[] b) {
        if (a.length != b.length)
            throw new IllegalArgumentException("Vector length mismatch");

        if (RustNativeLoader.isLibraryLoaded()) {
            try {
                return RustNativeLoader.nalgebra_vector_add(a, b);
            } catch (UnsatisfiedLinkError ignored) {
            }
        }

        float[] res = new float[a.length];
        for (int i = 0; i < a.length; i++) {
            res[i] = a[i] + b[i];
        }
        return res;
    }

    /**
     * Rotate vector by quaternion.
     * q = [x, y, z, w]
     */
    public static float[] rotateByQuaternion(float[] q, float[] v) {
        if (v == null || q == null || v.length != 3 || q.length != 4)
            throw new IllegalArgumentException("Invalid dimensions for quaternion rotation");

        float qx = q[0], qy = q[1], qz = q[2], qw = q[3];

        // Java implementation (optimized)
        // t = 2 * cross(q.xyz, v)
        float tx = 2f * (qy * v[2] - qz * v[1]);
        float ty = 2f * (qz * v[0] - qx * v[2]);
        float tz = 2f * (qx * v[1] - qy * v[0]);

        // v' = v + qw * t + cross(q.xyz, t)
        // cross(q.xyz, t)
        float crossX = qy * tz - qz * ty;
        float crossY = qz * tx - qx * tz;
        float crossZ = qx * ty - qy * tx;

        return new float[] {
                v[0] + qw * tx + crossX,
                v[1] + qw * ty + crossY,
                v[2] + qw * tz + crossZ
        };
    }

    /**
     * Dampens a vector by a scalar factor.
     * result = v * (1.0 - damping)
     */
    public static double[] damp(double x, double y, double z, double damping) {
        if (RustNativeLoader.isLibraryLoaded()) {
            try {
                return RustNativeLoader.rustperf_vector_damp(x, y, z, damping);
            } catch (UnsatisfiedLinkError ignored) {
                // Fallback
            }
        }

        double factor = 1.0 - damping;
        return new double[] { x * factor, y * factor, z * factor };
    }
}
