/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 */
package com.kneaf.core.mixin;

import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

@Mixin(Direction.class)
public abstract class EnumValuesMixin {

    // Can't easily shadow values() since it's synthetic static.
    // But we can overwrite it if we know the field.
    // Actually, Mixin doesn't support overwriting generic enum values() easily
    // because of internal handling.

    // Instead, let's look at `Direction.values()` specifically.
    // It is a very hot path.

    @Shadow
    private static Direction[] $VALUES; // The synthetic field holding values (mapped name might vary)

    @Unique
    private static final Direction[] kneaf$CACHED_VALUES = Direction.values();

    /**
     * @author KneafMod
     * @reason Eliminate array allocation on every values() call.
     */
    @Overwrite
    public static Direction[] values() {
        // Return cached array.
        // DANGER: If caller modifies this array, it breaks things.
        // But Direction is immutable and usually iterated.
        return kneaf$CACHED_VALUES;
    }

    // Also optimize Axis
    @Mixin(Direction.Axis.class)
    public static abstract class AxisMixin {
        @Unique
        private static final Direction.Axis[] kneaf$CACHED_AXIS = Direction.Axis.values();

        /**
         * @author KneafMod
         * @reason Cache values
         */
        @Overwrite
        public static Direction.Axis[] values() {
            return kneaf$CACHED_AXIS;
        }
    }
}
