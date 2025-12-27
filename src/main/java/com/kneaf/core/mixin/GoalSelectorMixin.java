package com.kneaf.core.mixin;

import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Optimizes GoalSelector by caching the FAILURE of goals.
 * If a goal.canUse() returns false, we assume it will likely allow false for a
 * few ticks.
 * This prevents checking expensive conditions (like searching for items/blocks)
 * every single tick.
 *
 * This is NOT throttling. It is result caching.
 */
@Mixin(GoalSelector.class)
public class GoalSelectorMixin {

    @Shadow
    @Final
    private Set<WrappedGoal> availableGoals;

    // Cache goal failures: Goal Instance -> GameTime when it can be checked again
    @Unique
    private final Map<Goal, Long> kneaf$failureCache = new WeakHashMap<>();

    @Unique
    private static final int MIN_CACHE_TICKS = 2; // Minimum ticks to assume failure persists
    @Unique
    private static final int MAX_CACHE_TICKS = 5; // Maximum ticks (randomized to avoid synchronized retries)

    // Helper to get current game time indirectly or use a simple counter
    @Unique
    private long kneaf$tickCounter = 0;

    /**
     * Redirect the call to WrappedGoal.canUse() inside the loop in tick().
     * We intercept this to check our cache first.
     */
    @Redirect(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/ai/goal/WrappedGoal;canUse()Z"))
    private boolean kneaf$cachedCanUse(WrappedGoal wrappedGoal) {
        Goal goal = wrappedGoal.getGoal();

        // Update local tick counter (approximate)
        kneaf$tickCounter++;

        // 1. Check Cache
        Long retryTime = kneaf$failureCache.get(goal);
        if (retryTime != null && kneaf$tickCounter < retryTime) {
            // It failed recently, assume it's still false
            return false;
        }

        // 2. Perform Actual Check
        boolean result = wrappedGoal.canUse();

        // 3. Update Cache on Failure
        if (!result) {
            // If it failed, don't check again for a random 2-5 ticks
            int delay = ThreadLocalRandom.current().nextInt(MIN_CACHE_TICKS, MAX_CACHE_TICKS + 1);
            kneaf$failureCache.put(goal, kneaf$tickCounter + delay);
        } else {
            // If it succeeded, remove any failure cache (shouldn't exist, but safe cleanup)
            kneaf$failureCache.remove(goal);
        }

        return result;
    }
}
