/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 */
package com.kneaf.core.mixin;

import net.minecraft.util.Mth;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;

@Mixin(Mth.class)
public abstract class FastMathMixin {

    @Unique
    private static final float[] kneaf$SIN_TABLE = new float[65536];

    static {
        for (int i = 0; i < 65536; ++i) {
            kneaf$SIN_TABLE[i] = (float) Math.sin((double) i * Math.PI * 2.0D / 65536.0D);
        }
    }

    /**
     * @author KneafMod
     * @reason Fast trigonometric lookup table (O(1)) vs Math.sin (native/slow)
     */
    @Overwrite
    public static float sin(float value) {
        return kneaf$SIN_TABLE[(int) (value * 10430.378F) & 65535];
    }

    /**
     * @author KneafMod
     * @reason Fast trigonometric lookup table (O(1)) vs Math.cos (native/slow)
     */
    @Overwrite
    public static float cos(float value) {
        return kneaf$SIN_TABLE[(int) (value * 10430.378F + 16384.0F) & 65535];
    }
}
