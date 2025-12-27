package com.kneaf.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Java wrapper for Rust-accelerated noise generation.
 * Provides high-performance Simplex/Perlin noise functions using the native
 * backend, with Java fallback implementation.
 */
public class RustNoise {
    private static final Logger LOGGER = LoggerFactory.getLogger(RustNoise.class);

    // Simplex noise permutation table
    private static final int[] PERM = new int[512];
    private static final int[] PERM_MOD12 = new int[512];

    // Gradient vectors for 2D
    private static final double[][] GRAD2 = {
            { 1, 1 }, { -1, 1 }, { 1, -1 }, { -1, -1 },
            { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 }
    };

    // Gradient vectors for 3D
    private static final double[][] GRAD3 = {
            { 1, 1, 0 }, { -1, 1, 0 }, { 1, -1, 0 }, { -1, -1, 0 },
            { 1, 0, 1 }, { -1, 0, 1 }, { 1, 0, -1 }, { -1, 0, -1 },
            { 0, 1, 1 }, { 0, -1, 1 }, { 0, 1, -1 }, { 0, -1, -1 }
    };

    // Skewing factors for 2D
    private static final double F2 = 0.5 * (Math.sqrt(3.0) - 1.0);
    private static final double G2 = (3.0 - Math.sqrt(3.0)) / 6.0;

    // Skewing factors for 3D
    private static final double F3 = 1.0 / 3.0;
    private static final double G3 = 1.0 / 6.0;

    static {
        // Initialize permutation table
        int[] p = { 151, 160, 137, 91, 90, 15, 131, 13, 201, 95, 96, 53, 194, 233, 7, 225,
                140, 36, 103, 30, 69, 142, 8, 99, 37, 240, 21, 10, 23, 190, 6, 148,
                247, 120, 234, 75, 0, 26, 197, 62, 94, 252, 219, 203, 117, 35, 11, 32,
                57, 177, 33, 88, 237, 149, 56, 87, 174, 20, 125, 136, 171, 168, 68, 175,
                74, 165, 71, 134, 139, 48, 27, 166, 77, 146, 158, 231, 83, 111, 229, 122,
                60, 211, 133, 230, 220, 105, 92, 41, 55, 46, 245, 40, 244, 102, 143, 54,
                65, 25, 63, 161, 1, 216, 80, 73, 209, 76, 132, 187, 208, 89, 18, 169,
                200, 196, 135, 130, 116, 188, 159, 86, 164, 100, 109, 198, 173, 186, 3, 64,
                52, 217, 226, 250, 124, 123, 5, 202, 38, 147, 118, 126, 255, 82, 85, 212,
                207, 206, 59, 227, 47, 16, 58, 17, 182, 189, 28, 42, 223, 183, 170, 213,
                119, 248, 152, 2, 44, 154, 163, 70, 221, 153, 101, 155, 167, 43, 172, 9,
                129, 22, 39, 253, 19, 98, 108, 110, 79, 113, 224, 232, 178, 185, 112, 104,
                218, 246, 97, 228, 251, 34, 242, 193, 238, 210, 144, 12, 191, 179, 162, 241,
                81, 51, 145, 235, 249, 14, 239, 107, 49, 192, 214, 31, 181, 199, 106, 157,
                184, 84, 204, 176, 115, 121, 50, 45, 127, 4, 150, 254, 138, 236, 205, 93,
                222, 114, 67, 29, 24, 72, 243, 141, 128, 195, 78, 66, 215, 61, 156, 180 };

        for (int i = 0; i < 256; i++) {
            PERM[i] = p[i];
            PERM[256 + i] = p[i];
            PERM_MOD12[i] = p[i] % 12;
            PERM_MOD12[256 + i] = p[i] % 12;
        }
    }

    // Native method declarations
    public static native double noise2d(double x, double z, int seed, double frequency);

    public static native double noise3d(double x, double y, double z, int seed, double frequency);

    public static native double[] batchNoise2d(double xStart, double zStart, int width, int depth, int seed,
            double frequency);

    /**
     * Check if native noise generation is available
     */
    public static boolean isAvailable() {
        return RustNativeLoader.isLibraryLoaded();
    }

    /**
     * Generate 2D noise with fallback to Java implementation if native is
     * unavailable
     */
    public static double getNoise2d(double x, double z, int seed, double frequency) {
        if (isAvailable()) {
            try {
                return noise2d(x, z, seed, frequency);
            } catch (UnsatisfiedLinkError e) {
                LOGGER.warn("Native noise2d failed, falling back to Java: {}", e.getMessage());
            }
        }
        // Java fallback using Simplex noise
        return simplexNoise2d(x * frequency, z * frequency, seed);
    }

    /**
     * Generate 3D noise with fallback
     */
    public static double getNoise3d(double x, double y, double z, int seed, double frequency) {
        if (isAvailable()) {
            try {
                return noise3d(x, y, z, seed, frequency);
            } catch (UnsatisfiedLinkError e) {
                // Warning suppressed to avoid log spam
            }
        }
        // Java fallback using Simplex noise
        return simplexNoise3d(x * frequency, y * frequency, z * frequency, seed);
    }

    /**
     * Java implementation of 2D Simplex noise.
     */
    private static double simplexNoise2d(double xin, double yin, int seed) {
        // Apply seed offset
        xin += seed * 0.013;
        yin += seed * 0.017;

        double n0, n1, n2; // Noise contributions from the three corners

        // Skew the input space to determine which simplex cell we're in
        double s = (xin + yin) * F2;
        int i = fastFloor(xin + s);
        int j = fastFloor(yin + s);

        double t = (i + j) * G2;
        double X0 = i - t; // Unskew the cell origin back to (x,y) space
        double Y0 = j - t;
        double x0 = xin - X0; // The x,y distances from the cell origin
        double y0 = yin - Y0;

        // Determine which simplex we are in
        int i1, j1; // Offsets for second corner of simplex in (i,j) coords
        if (x0 > y0) {
            i1 = 1;
            j1 = 0; // lower triangle
        } else {
            i1 = 0;
            j1 = 1; // upper triangle
        }

        double x1 = x0 - i1 + G2;
        double y1 = y0 - j1 + G2;
        double x2 = x0 - 1.0 + 2.0 * G2;
        double y2 = y0 - 1.0 + 2.0 * G2;

        // Work out the hashed gradient indices of the three simplex corners
        int ii = i & 255;
        int jj = j & 255;
        int gi0 = PERM[ii + PERM[jj]] % 8;
        int gi1 = PERM[ii + i1 + PERM[jj + j1]] % 8;
        int gi2 = PERM[ii + 1 + PERM[jj + 1]] % 8;

        // Calculate the contribution from the three corners
        double t0 = 0.5 - x0 * x0 - y0 * y0;
        if (t0 < 0) {
            n0 = 0.0;
        } else {
            t0 *= t0;
            n0 = t0 * t0 * dot2d(GRAD2[gi0], x0, y0);
        }

        double t1 = 0.5 - x1 * x1 - y1 * y1;
        if (t1 < 0) {
            n1 = 0.0;
        } else {
            t1 *= t1;
            n1 = t1 * t1 * dot2d(GRAD2[gi1], x1, y1);
        }

        double t2 = 0.5 - x2 * x2 - y2 * y2;
        if (t2 < 0) {
            n2 = 0.0;
        } else {
            t2 *= t2;
            n2 = t2 * t2 * dot2d(GRAD2[gi2], x2, y2);
        }

        // Add contributions from each corner to get the final noise value.
        // The result is scaled to return values in the range [-1, 1]
        return 70.0 * (n0 + n1 + n2);
    }

    /**
     * Java implementation of 3D Simplex noise.
     */
    private static double simplexNoise3d(double xin, double yin, double zin, int seed) {
        // Apply seed offset
        xin += seed * 0.013;
        yin += seed * 0.017;
        zin += seed * 0.019;

        double n0, n1, n2, n3; // Noise contributions from the four corners

        // Skew the input space to determine which simplex cell we're in
        double s = (xin + yin + zin) * F3;
        int i = fastFloor(xin + s);
        int j = fastFloor(yin + s);
        int k = fastFloor(zin + s);

        double t = (i + j + k) * G3;
        double X0 = i - t;
        double Y0 = j - t;
        double Z0 = k - t;
        double x0 = xin - X0;
        double y0 = yin - Y0;
        double z0 = zin - Z0;

        // Determine which simplex we are in
        int i1, j1, k1;
        int i2, j2, k2;

        if (x0 >= y0) {
            if (y0 >= z0) {
                i1 = 1;
                j1 = 0;
                k1 = 0;
                i2 = 1;
                j2 = 1;
                k2 = 0;
            } else if (x0 >= z0) {
                i1 = 1;
                j1 = 0;
                k1 = 0;
                i2 = 1;
                j2 = 0;
                k2 = 1;
            } else {
                i1 = 0;
                j1 = 0;
                k1 = 1;
                i2 = 1;
                j2 = 0;
                k2 = 1;
            }
        } else {
            if (y0 < z0) {
                i1 = 0;
                j1 = 0;
                k1 = 1;
                i2 = 0;
                j2 = 1;
                k2 = 1;
            } else if (x0 < z0) {
                i1 = 0;
                j1 = 1;
                k1 = 0;
                i2 = 0;
                j2 = 1;
                k2 = 1;
            } else {
                i1 = 0;
                j1 = 1;
                k1 = 0;
                i2 = 1;
                j2 = 1;
                k2 = 0;
            }
        }

        double x1 = x0 - i1 + G3;
        double y1 = y0 - j1 + G3;
        double z1 = z0 - k1 + G3;
        double x2 = x0 - i2 + 2.0 * G3;
        double y2 = y0 - j2 + 2.0 * G3;
        double z2 = z0 - k2 + 2.0 * G3;
        double x3 = x0 - 1.0 + 3.0 * G3;
        double y3 = y0 - 1.0 + 3.0 * G3;
        double z3 = z0 - 1.0 + 3.0 * G3;

        int ii = i & 255;
        int jj = j & 255;
        int kk = k & 255;
        int gi0 = PERM_MOD12[ii + PERM[jj + PERM[kk]]];
        int gi1 = PERM_MOD12[ii + i1 + PERM[jj + j1 + PERM[kk + k1]]];
        int gi2 = PERM_MOD12[ii + i2 + PERM[jj + j2 + PERM[kk + k2]]];
        int gi3 = PERM_MOD12[ii + 1 + PERM[jj + 1 + PERM[kk + 1]]];

        double t0 = 0.6 - x0 * x0 - y0 * y0 - z0 * z0;
        if (t0 < 0) {
            n0 = 0.0;
        } else {
            t0 *= t0;
            n0 = t0 * t0 * dot3d(GRAD3[gi0], x0, y0, z0);
        }

        double t1 = 0.6 - x1 * x1 - y1 * y1 - z1 * z1;
        if (t1 < 0) {
            n1 = 0.0;
        } else {
            t1 *= t1;
            n1 = t1 * t1 * dot3d(GRAD3[gi1], x1, y1, z1);
        }

        double t2 = 0.6 - x2 * x2 - y2 * y2 - z2 * z2;
        if (t2 < 0) {
            n2 = 0.0;
        } else {
            t2 *= t2;
            n2 = t2 * t2 * dot3d(GRAD3[gi2], x2, y2, z2);
        }

        double t3 = 0.6 - x3 * x3 - y3 * y3 - z3 * z3;
        if (t3 < 0) {
            n3 = 0.0;
        } else {
            t3 *= t3;
            n3 = t3 * t3 * dot3d(GRAD3[gi3], x3, y3, z3);
        }

        // Add contributions and scale to [-1, 1]
        return 32.0 * (n0 + n1 + n2 + n3);
    }

    private static int fastFloor(double x) {
        int xi = (int) x;
        return x < xi ? xi - 1 : xi;
    }

    private static double dot2d(double[] g, double x, double y) {
        return g[0] * x + g[1] * y;
    }

    private static double dot3d(double[] g, double x, double y, double z) {
        return g[0] * x + g[1] * y + g[2] * z;
    }
}
