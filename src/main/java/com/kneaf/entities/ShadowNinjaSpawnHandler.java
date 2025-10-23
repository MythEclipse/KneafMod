package com.kneaf.entities;

import com.kneaf.core.KneafCore;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.MobSpawnType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerWakeUpEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Handler untuk spawn Shadow Zombie Ninja secara natural
 * - Hanya spawn jika player tidak tidur selama 10 hari (120,000 ticks)
 * - Maksimal 1 ninja per player
 * - Despawn jika target player mati
 */
@EventBusSubscriber(modid = KneafCore.MODID, bus = EventBusSubscriber.Bus.GAME)
public class ShadowNinjaSpawnHandler {
    
    // Track last sleep time per player
    private static final Map<UUID, Long> playerLastSleepTime = new HashMap<>();
    
    // Track active ninja per player
    private static final Map<UUID, ShadowZombieNinja> activeNinjas = new HashMap<>();
    
    // Constants
    private static final long DAYS_BEFORE_SPAWN = 10; // 10 hari
    private static final long TICKS_PER_DAY = 24000; // 1 hari = 24000 ticks
    private static final long TICKS_BEFORE_SPAWN = DAYS_BEFORE_SPAWN * TICKS_PER_DAY; // 240,000 ticks (10 hari)
    private static final int CHECK_INTERVAL = 200; // Check every 10 seconds
    
    private static int tickCounter = 0;
    
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        tickCounter++;
        
        // Only check every 10 seconds to reduce performance impact
        if (tickCounter < CHECK_INTERVAL) {
            return;
        }
        tickCounter = 0;
        
        // Check all players
        for (ServerLevel level : event.getServer().getAllLevels()) {
            for (ServerPlayer player : level.players()) {
                checkAndSpawnNinja(player, level);
                checkNinjaStatus(player);
            }
        }
    }
    
    @SubscribeEvent
    public static void onPlayerWakeUp(PlayerWakeUpEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            updatePlayerSleepTime(player);
        }
    }
    
    @SubscribeEvent
    public static void onPlayerLogoutEvent(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            handlePlayerLogout(player);
        }
    }
    
    /**
     * Update player's last sleep time
     */
    public static void updatePlayerSleepTime(ServerPlayer player) {
        playerLastSleepTime.put(player.getUUID(), player.level().getGameTime());
        
        // Remove ninja if exists when player sleeps
        ShadowZombieNinja ninja = activeNinjas.get(player.getUUID());
        if (ninja != null && !ninja.isRemoved()) {
            despawnNinja(ninja);
            activeNinjas.remove(player.getUUID());
        }
    }
    
    /**
     * Check if ninja should spawn for this player
     */
    private static void checkAndSpawnNinja(ServerPlayer player, ServerLevel level) {
        // Skip if player is in creative or spectator
        if (player.isCreative() || player.isSpectator()) {
            return;
        }
        
        // Skip if already has active ninja
        ShadowZombieNinja existingNinja = activeNinjas.get(player.getUUID());
        if (existingNinja != null && !existingNinja.isRemoved()) {
            return;
        }
        
        // Get player's last sleep time
        Long lastSleep = playerLastSleepTime.get(player.getUUID());
        if (lastSleep == null) {
            // First time tracking this player - use current time
            playerLastSleepTime.put(player.getUUID(), level.getGameTime());
            return;
        }
        
        // Calculate time since last sleep
        long timeSinceLastSleep = level.getGameTime() - lastSleep;
        
        // Check if player hasn't slept for 10 days
        if (timeSinceLastSleep >= TICKS_BEFORE_SPAWN) {
            spawnNinja(player, level);
        }
    }
    
    /**
     * Spawn Shadow Zombie Ninja near player
     */
    private static void spawnNinja(ServerPlayer player, ServerLevel level) {
        // Find spawn position near player (10-20 blocks away)
        BlockPos playerPos = player.blockPosition();
        BlockPos spawnPos = findSafeSpawnPosition(level, playerPos);
        
        if (spawnPos == null) {
            return; // No safe spawn position found
        }
        
        // Create and spawn ninja
        ShadowZombieNinja ninja = ModEntities.SHADOW_ZOMBIE_NINJA.get().create(level);
        if (ninja != null) {
            ninja.setPos(spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5);
            ninja.setTarget(player);
            ninja.finalizeSpawn(level, level.getCurrentDifficultyAt(spawnPos), MobSpawnType.EVENT, null);
            
            level.addFreshEntity(ninja);
            activeNinjas.put(player.getUUID(), ninja);
            
            // Send warning message to player
            player.sendSystemMessage(
                net.minecraft.network.chat.Component.literal("§4§l[WARNING] §cA Shadow Ninja has been summoned to hunt you down!")
                    .append("\n§7You haven't slept for 10 days...")
            );
            
            // Play dramatic sound
            player.playSound(net.minecraft.sounds.SoundEvents.WARDEN_EMERGE, 1.0F, 0.8F);
        }
    }
    
    /**
     * Find safe spawn position near player
     */
    private static BlockPos findSafeSpawnPosition(ServerLevel level, BlockPos playerPos) {
        // Try to find position 10-20 blocks away from player
        for (int attempts = 0; attempts < 10; attempts++) {
            double angle = level.random.nextDouble() * Math.PI * 2;
            double distance = 10 + level.random.nextDouble() * 10; // 10-20 blocks
            
            int x = playerPos.getX() + (int)(Math.cos(angle) * distance);
            int z = playerPos.getZ() + (int)(Math.sin(angle) * distance);
            
            // Find ground level
            BlockPos testPos = new BlockPos(x, playerPos.getY(), z);
            for (int y = playerPos.getY() + 10; y > playerPos.getY() - 10; y--) {
                testPos = new BlockPos(x, y, z);
                if (level.getBlockState(testPos).isAir() && 
                    level.getBlockState(testPos.above()).isAir() && 
                    !level.getBlockState(testPos.below()).isAir()) {
                    return testPos;
                }
            }
        }
        
        return null;
    }
    
    /**
     * Check ninja status and despawn if target is dead
     */
    private static void checkNinjaStatus(ServerPlayer player) {
        ShadowZombieNinja ninja = activeNinjas.get(player.getUUID());
        if (ninja != null) {
            // If player is dead or ninja lost target, despawn ninja
            if (player.isDeadOrDying() || ninja.getTarget() != player) {
                despawnNinja(ninja);
                activeNinjas.remove(player.getUUID());
            }
        }
    }
    
    /**
     * Despawn ninja with Warden-like animation
     */
    private static void despawnNinja(ShadowZombieNinja ninja) {
        if (ninja.level() instanceof ServerLevel serverLevel) {
            // Warden-like dig down animation and effects
            BlockPos pos = ninja.blockPosition();
            
            // Massive particle effects (like Warden digging)
            for (int i = 0; i < 50; i++) {
                double offsetX = (ninja.getRandom().nextDouble() - 0.5) * 2.0;
                double offsetY = ninja.getRandom().nextDouble() * 2.0;
                double offsetZ = (ninja.getRandom().nextDouble() - 0.5) * 2.0;
                
                serverLevel.sendParticles(net.minecraft.core.particles.ParticleTypes.LARGE_SMOKE,
                    pos.getX() + 0.5 + offsetX, pos.getY() + offsetY, pos.getZ() + 0.5 + offsetZ,
                    1, 0.0D, 0.0D, 0.0D, 0.0);
                    
                serverLevel.sendParticles(net.minecraft.core.particles.ParticleTypes.SCULK_SOUL,
                    pos.getX() + 0.5 + offsetX, pos.getY() + offsetY, pos.getZ() + 0.5 + offsetZ,
                    1, 0.0D, -0.1D, 0.0D, 0.0);
                    
                serverLevel.sendParticles(net.minecraft.core.particles.ParticleTypes.SOUL_FIRE_FLAME,
                    pos.getX() + 0.5 + offsetX, pos.getY() + offsetY, pos.getZ() + 0.5 + offsetZ,
                    1, 0.0D, 0.0D, 0.0D, 0.0);
            }
            
            // Sound effects (like Warden) - play from ninja position
            ninja.level().playSound(null, ninja.getX(), ninja.getY(), ninja.getZ(), 
                net.minecraft.sounds.SoundEvents.WARDEN_DIG, 
                net.minecraft.sounds.SoundSource.HOSTILE, 1.0F, 1.0F);
            ninja.level().playSound(null, ninja.getX(), ninja.getY(), ninja.getZ(), 
                net.minecraft.sounds.SoundEvents.SOUL_ESCAPE, 
                net.minecraft.sounds.SoundSource.HOSTILE, 1.0F, 0.8F);
            
            // Remove entity
            ninja.discard();
        }
    }
    
    /**
     * Public method to despawn ninja (called from entity tick)
     */
    public static void despawnNinjaPublic(ShadowZombieNinja ninja) {
        despawnNinja(ninja);
    }
    
    /**
     * Clean up when player logs out
     */
    public static void handlePlayerLogout(ServerPlayer player) {
        // Keep last sleep time tracked
        // Remove active ninja if exists
        ShadowZombieNinja ninja = activeNinjas.remove(player.getUUID());
        if (ninja != null && !ninja.isRemoved()) {
            despawnNinja(ninja);
        }
    }
    
    /**
     * Get active ninja for player (for debugging)
     */
    public static ShadowZombieNinja getActiveNinja(UUID playerId) {
        return activeNinjas.get(playerId);
    }
}
