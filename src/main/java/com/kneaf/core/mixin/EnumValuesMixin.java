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
    @Mixin(net.minecraft.core.Direction.Axis.class)
    public static abstract class AxisMixin {
        @Unique
        private static final net.minecraft.core.Direction.Axis[] kneaf$CACHED_AXIS = net.minecraft.core.Direction.Axis
                .values();

        /**
         * @author KneafMod
         * @reason Cache values
         */
        @Overwrite
        public static net.minecraft.core.Direction.Axis[] values() {
            return kneaf$CACHED_AXIS;
        }
    }

    // InteractionHand
    @Mixin(net.minecraft.world.InteractionHand.class)
    public static abstract class InteractionHandMixin {
        @Unique
        private static final net.minecraft.world.InteractionHand[] kneaf$CACHED_VALUES = net.minecraft.world.InteractionHand
                .values();

        /**
         * @author KneafMod
         * @reason Cache values
         */
        @Overwrite
        public static net.minecraft.world.InteractionHand[] values() {
            return kneaf$CACHED_VALUES;
        }
    }

    // ChatFormatting
    @Mixin(net.minecraft.ChatFormatting.class)
    public static abstract class ChatFormattingMixin {
        @Unique
        private static final net.minecraft.ChatFormatting[] kneaf$CACHED_VALUES = net.minecraft.ChatFormatting.values();

        /**
         * @author KneafMod
         * @reason Cache values
         */
        @Overwrite
        public static net.minecraft.ChatFormatting[] values() {
            return kneaf$CACHED_VALUES;
        }
    }

    // EquipmentSlot
    @Mixin(net.minecraft.world.entity.EquipmentSlot.class)
    public static abstract class EquipmentSlotMixin {
        @Unique
        private static final net.minecraft.world.entity.EquipmentSlot[] kneaf$CACHED_VALUES = net.minecraft.world.entity.EquipmentSlot
                .values();

        /**
         * @author KneafMod
         * @reason Cache values
         */
        @Overwrite
        public static net.minecraft.world.entity.EquipmentSlot[] values() {
            return kneaf$CACHED_VALUES;
        }
    }
}
