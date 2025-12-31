package com.kneaf.core.mixin;

import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(Direction.class)
public abstract class EnumValuesMixin {
    @Shadow
    private static Direction[] $VALUES;

    @Overwrite
    public static Direction[] values() {
        return $VALUES;
    }

    @Mixin(Direction.Axis.class)
    public static abstract class AxisMixin {
        @Shadow
        private static Direction.Axis[] $VALUES;
        @Overwrite
        public static Direction.Axis[] values() {
            return $VALUES;
        }
    }
}
