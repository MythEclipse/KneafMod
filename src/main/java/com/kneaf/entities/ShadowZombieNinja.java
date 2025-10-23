package com.kneaf.entities;
import com.kneaf.core.OptimizationInjector;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.MoveTowardsTargetGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.RandomStrollGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import java.util.EnumSet;
import javax.annotation.Nonnull;

public class ShadowZombieNinja extends Zombie {
    private final ServerBossEvent bossEvent = new ServerBossEvent(
        net.minecraft.network.chat.Component.literal("Hayabusa Shadow Ninja"),
        BossEvent.BossBarColor.PURPLE,
        BossEvent.BossBarOverlay.PROGRESS
    );

    // Hayabusa skill cooldowns
    private int phantomShurikenCooldown = 0;
    private int quadShadowCooldown = 0;
    private int shadowKillCooldown = 0;
    private int shadowKillPassiveStacks = 0;
    
    // Skill constants (balanced like Hayabusa)
    private static final int PHANTOM_SHURIKEN_COOLDOWN = 50; // 2.5 seconds - Skill 1
    private static final int QUAD_SHADOW_COOLDOWN = 360; // 18 seconds - Skill 2 (diperpanjang)
    private static final int SHADOW_KILL_COOLDOWN = 600; // 30 seconds - Ultimate
    private static final int MAX_PASSIVE_STACKS = 4;
    private static final float PASSIVE_DAMAGE_MULTIPLIER = 1.2f;
    
    // Shadow clones for Quad Shadow skill
    private final java.util.List<Vec3> shadowClones = new java.util.ArrayList<>();
    private final java.util.Set<Integer> usedShadowClones = new java.util.HashSet<>(); // Track which shadows have been used
    private int shadowCloneDuration = 0;
    private static final int SHADOW_CLONE_DURATION = 200; // 10 seconds
    private static final double SHADOW_CLONE_DISTANCE = 10.0; // 10 blocks dari tengah (seperti Hayabusa)

    public ShadowZombieNinja(EntityType<? extends Zombie> entityType, Level level) {
        super(entityType, level);
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(1, new PhantomShurikenGoal(this));
        this.goalSelector.addGoal(2, new QuadShadowGoal(this));
        this.goalSelector.addGoal(3, new ShadowTeleportGoal(this)); // NEW: Teleport between clones (NO cooldown)
        this.goalSelector.addGoal(4, new ShadowKillGoal(this));
        this.goalSelector.addGoal(5, new MeleeAttackGoal(this, 1.2D, false));
        this.goalSelector.addGoal(6, new MoveTowardsTargetGoal(this, 1.2D, 32.0F));
        this.goalSelector.addGoal(7, new RandomStrollGoal(this, 1.0D));
        this.goalSelector.addGoal(8, new RandomLookAroundGoal(this));

        this.targetSelector.addGoal(1, new HurtByTargetGoal(this));
        this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, ServerPlayer.class, true));
        this.targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(this, Villager.class, true));
    }

    @Override
    public void tick() {
        super.tick();
        
        // Check if target player is dead - despawn with animation
        if (!this.level().isClientSide() && this.getTarget() instanceof ServerPlayer targetPlayer) {
            if (targetPlayer.isDeadOrDying()) {
                // Trigger despawn through handler
                if (this.level() instanceof ServerLevel) {
                    // Let handler do the despawn animation
                    ShadowNinjaSpawnHandler.despawnNinjaPublic(this);
                    return;
                }
            }
        }
        
        // Update skill cooldowns
        if (phantomShurikenCooldown > 0) {
            phantomShurikenCooldown--;
        }
        if (quadShadowCooldown > 0) {
            quadShadowCooldown--;
        }
        if (shadowKillCooldown > 0) {
            shadowKillCooldown--;
        }
        if (shadowCloneDuration > 0) {
            shadowCloneDuration--;
            
            // Check if server side
            boolean isServerSide = this.level() instanceof net.minecraft.server.level.ServerLevel;
            net.minecraft.server.level.ServerLevel serverLevel = isServerSide ? 
                (net.minecraft.server.level.ServerLevel) this.level() : null;
            
            // Continuous particle effects for active shadow clones with SERVER-SIDE sync
            for (Vec3 clonePos : shadowClones) {
                // Add particles every tick to make clones HIGHLY visible
                if (serverLevel != null) {
                    for (int i = 0; i < 5; i++) { // Increased from 3 to 5 for more visibility
                        double offsetX = (this.random.nextDouble() - 0.5) * 0.5;
                        double offsetY = this.random.nextDouble() * 2.0;
                        double offsetZ = (this.random.nextDouble() - 0.5) * 0.5;
                        
                        serverLevel.sendParticles(ParticleTypes.PORTAL, 
                            clonePos.x + offsetX, clonePos.y + offsetY, clonePos.z + offsetZ,
                            1, 0.0D, 0.0D, 0.0D, 0.0);
                        serverLevel.sendParticles(ParticleTypes.WITCH, 
                            clonePos.x + offsetX, clonePos.y + offsetY, clonePos.z + offsetZ,
                            1, 0.0D, 0.0D, 0.0D, 0.0);
                        serverLevel.sendParticles(ParticleTypes.ENCHANT, 
                            clonePos.x + offsetX, clonePos.y + offsetY, clonePos.z + offsetZ,
                            1, 0.0D, 0.0D, 0.0D, 0.0);
                    }
                    
                    // Occasional flash effect - more frequent
                    if (this.tickCount % 5 == 0) { // Changed from 10 to 5
                        serverLevel.sendParticles(ParticleTypes.ENCHANTED_HIT, 
                            clonePos.x, clonePos.y + 1, clonePos.z,
                            3, 0.2D, 0.2D, 0.2D, 0.0);
                        serverLevel.sendParticles(ParticleTypes.GLOW, 
                            clonePos.x, clonePos.y + 1, clonePos.z,
                            2, 0.3D, 0.3D, 0.3D, 0.0);
                    }
                }
            }
            
            if (shadowCloneDuration <= 0) {
                // Fade out effect when clones disappear
                if (serverLevel != null) {
                    for (Vec3 clonePos : shadowClones) {
                        for (int i = 0; i < 15; i++) {
                            double offsetX = (this.random.nextDouble() - 0.5) * 1.0;
                            double offsetY = this.random.nextDouble() * 2.0;
                            double offsetZ = (this.random.nextDouble() - 0.5) * 1.0;
                            serverLevel.sendParticles(ParticleTypes.SMOKE, 
                                clonePos.x + offsetX, clonePos.y + offsetY, clonePos.z + offsetZ,
                                1, 0.0D, 0.05D, 0.0D, 0.0);
                        }
                    }
                }
                shadowClones.clear();
                usedShadowClones.clear(); // Reset used shadows
            }
        }
        
        // Update passive stacks decay
        if (shadowKillPassiveStacks > 0 && this.tickCount % 40 == 0) { // Decay every 2 seconds
            shadowKillPassiveStacks--;
        }
        
        // Ninja movement trail effect
        if (this.getDeltaMovement().lengthSqr() > 0.01) {
            this.level().addParticle(ParticleTypes.SMOKE, 
                this.getX(), this.getY() + 0.1, this.getZ(), 
                0.0D, 0.0D, 0.0D);
            
            // Extra effects when moving fast
            if (this.getDeltaMovement().lengthSqr() > 0.1) {
                this.level().addParticle(ParticleTypes.PORTAL, 
                    this.getX(), this.getY() + 0.5, this.getZ(), 
                    0.0D, 0.0D, 0.0D);
            }
        }
        
        // Ambient particle effects for boss aura
        if (this.tickCount % 5 == 0) {
            double offsetX = (this.random.nextDouble() - 0.5) * 2.0;
            double offsetY = this.random.nextDouble() * 2.0;
            double offsetZ = (this.random.nextDouble() - 0.5) * 2.0;
            this.level().addParticle(ParticleTypes.PORTAL, 
                this.getX() + offsetX, this.getY() + offsetY, this.getZ() + offsetZ, 
                0.0D, 0.0D, 0.0D);
        }
    }

    @Override
    public boolean doHurtTarget(@Nonnull Entity target) {
        boolean flag = super.doHurtTarget(target);
        if (flag && target instanceof LivingEntity livingTarget) {
            // Hayabusa passive: Shadow Kill enhanced attacks
            // Each stack increases damage by 15%
            float baseDamage = 6.0F;
            float passiveMultiplier = 1.0f + (shadowKillPassiveStacks * 0.15f);
            float totalDamage = baseDamage * passiveMultiplier;
            
            livingTarget.hurt(this.damageSources().mobAttack(this), totalDamage);
            
            // Use Rust optimization for passive stack calculation
            shadowKillPassiveStacks = OptimizationInjector.calculatePassiveStacks(
                shadowKillPassiveStacks, true, MAX_PASSIVE_STACKS
            );
            
            // Enhanced visual feedback for melee attacks
            for (int i = 0; i < 10; i++) {
                double offsetX = (this.random.nextDouble() - 0.5) * 0.5;
                double offsetY = (this.random.nextDouble() - 0.5) * 0.5;
                double offsetZ = (this.random.nextDouble() - 0.5) * 0.5;
                this.level().addParticle(ParticleTypes.CRIT, 
                    livingTarget.getX() + offsetX, livingTarget.getY() + 1 + offsetY, livingTarget.getZ() + offsetZ, 
                    0.0D, 0.0D, 0.0D);
                this.level().addParticle(ParticleTypes.ENCHANTED_HIT, 
                    livingTarget.getX() + offsetX, livingTarget.getY() + 1 + offsetY, livingTarget.getZ() + offsetZ, 
                    0.0D, 0.0D, 0.0D);
            }
            
            // Visual feedback for passive stacks
            if (shadowKillPassiveStacks > 0) {
                for (int i = 0; i < shadowKillPassiveStacks; i++) {
                    this.level().addParticle(ParticleTypes.SOUL_FIRE_FLAME, 
                        this.getX(), this.getY() + 1.5, this.getZ(), 
                        (this.random.nextDouble() - 0.5) * 0.1, 0.1D, (this.random.nextDouble() - 0.5) * 0.1);
                }
            }
            
            // Sound effects
            this.playSound(SoundEvents.PLAYER_ATTACK_CRIT, 1.0F, 1.2F);
        }
        return flag;
    }

    @Override
    public void startSeenByPlayer(@Nonnull ServerPlayer player) {
        super.startSeenByPlayer(player);
        this.bossEvent.addPlayer(player);
    }

    @Override
    public void stopSeenByPlayer(@Nonnull ServerPlayer player) {
        super.stopSeenByPlayer(player);
        this.bossEvent.removePlayer(player);
    }

    @Override
    public void customServerAiStep() {
        super.customServerAiStep();
        this.bossEvent.setProgress(this.getHealth() / this.getMaxHealth());
    }


    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
            .add(Attributes.MAX_HEALTH, 250.0D) // Increased HP for boss
            .add(Attributes.MOVEMENT_SPEED, 0.35D) // Fast like assassin
            .add(Attributes.ATTACK_DAMAGE, 12.0D) // High burst damage
            .add(Attributes.FOLLOW_RANGE, 40.0D) // Large detection range
            .add(Attributes.ARMOR, 6.0D) // Moderate armor
            .add(Attributes.ATTACK_KNOCKBACK, 0.5D) // Slight knockback
            .add(Attributes.SPAWN_REINFORCEMENTS_CHANCE, 0.0D);
    }

    // Hayabusa Skills Implementation with Rust Optimization
    
    private void performPhantomShuriken(LivingEntity target) {
        if (phantomShurikenCooldown > 0 || target == null) return;

        Vec3 targetPos = target.position();
        Vec3 currentPos = this.position();

        // Direct targeting - calculate vector directly to target
        double d0 = targetPos.x - currentPos.x;
        double d1 = targetPos.y + target.getEyeHeight() * 0.5 - (currentPos.y + this.getEyeHeight());
        double d2 = targetPos.z - currentPos.z;
        
        // Use Rust-optimized distance calculation
        double d3 = com.kneaf.core.RustNativeLoader.vectorLength(d0, 0.0, d2);

        // Create shuriken with precise targeting
        Arrow shuriken = new Arrow(this.level(), this, new ItemStack(Items.NETHERITE_SCRAP), new ItemStack(Items.IRON_SWORD));
        shuriken.setPos(this.getX(), this.getEyeY() - 0.1D, this.getZ());
        // Direct shoot to target with no inaccuracy
        shuriken.shoot(d0, d1 + d3 * 0.2D, d2, 2.5F, 0.0F);
        shuriken.setBaseDamage(8.0D);
        shuriken.setNoGravity(true);
        shuriken.pickup = net.minecraft.world.entity.projectile.AbstractArrow.Pickup.DISALLOWED;
        
        if (!this.level().isClientSide()) {
            ((net.minecraft.server.level.ServerLevel)this.level()).addFreshEntity(shuriken);
        }
        
        // Enhanced visual effects for shuriken throw
        for (int i = 0; i < 10; i++) {
            double offsetX = (this.random.nextDouble() - 0.5) * 0.3;
            double offsetY = (this.random.nextDouble() - 0.5) * 0.3;
            double offsetZ = (this.random.nextDouble() - 0.5) * 0.3;
            this.level().addParticle(ParticleTypes.SONIC_BOOM, 
                this.getX() + offsetX, this.getY() + 1 + offsetY, this.getZ() + offsetZ, 
                0.0D, 0.0D, 0.0D);
            this.level().addParticle(ParticleTypes.SWEEP_ATTACK, 
                this.getX() + offsetX, this.getY() + 1 + offsetY, this.getZ() + offsetZ, 
                0.0D, 0.0D, 0.0D);
        }
        
        // Trail particles towards target
        for (int i = 0; i < 5; i++) {
            double progress = i / 5.0;
            double particleX = this.getX() + d0 * progress * 0.2;
            double particleY = this.getEyeY() + d1 * progress * 0.2;
            double particleZ = this.getZ() + d2 * progress * 0.2;
            this.level().addParticle(ParticleTypes.CRIT, particleX, particleY, particleZ, 0.0D, 0.0D, 0.0D);
            this.level().addParticle(ParticleTypes.ENCHANTED_HIT, particleX, particleY, particleZ, 0.0D, 0.0D, 0.0D);
        }
        
        this.playSound(SoundEvents.PLAYER_ATTACK_SWEEP, 1.0F, 1.5F);
        this.playSound(SoundEvents.ENDER_DRAGON_FLAP, 1.0F, 1.8F);

        phantomShurikenCooldown = PHANTOM_SHURIKEN_COOLDOWN;
        
        // Schedule shuriken return after 2 seconds
        this.level().scheduleTick(this.blockPosition(), this.level().getBlockState(this.blockPosition()).getBlock(), 40);
    }
    
    private void performQuadShadow() {
        if (quadShadowCooldown > 0) return;

        Vec3 currentPos = this.position();
        
        // Check if server side for particle spawning
        boolean isServerSide = this.level() instanceof net.minecraft.server.level.ServerLevel;
        net.minecraft.server.level.ServerLevel serverLevel = isServerSide ? 
            (net.minecraft.server.level.ServerLevel) this.level() : null;
        
        // Massive visual effect at starting position with SERVER-SIDE particles
        if (serverLevel != null) {
            for (int i = 0; i < 30; i++) {
                double offsetX = (this.random.nextDouble() - 0.5) * 2.0;
                double offsetY = this.random.nextDouble() * 2.0;
                double offsetZ = (this.random.nextDouble() - 0.5) * 2.0;
                serverLevel.sendParticles(ParticleTypes.PORTAL, 
                    currentPos.x + offsetX, currentPos.y + offsetY, currentPos.z + offsetZ,
                    1, 0.0D, 0.1D, 0.0D, 0.0);
                serverLevel.sendParticles(ParticleTypes.SMOKE, 
                    currentPos.x + offsetX, currentPos.y + offsetY, currentPos.z + offsetZ,
                    1, 0.0D, 0.05D, 0.0D, 0.0);
            }
        }
        
        // Use Rust optimization for shadow clone positions
        double[][] clonePositions = OptimizationInjector.calculateQuadShadowPositions(
            currentPos.x, currentPos.y, currentPos.z, SHADOW_CLONE_DISTANCE // 10 blocks dari tengah
        );
        
        // Create 4 shadow clones around the ninja with massive effects
        shadowClones.clear();
        usedShadowClones.clear(); // Reset used shadows tracker
        for (int i = 0; i < 4; i++) {
            // Adjust Y-coordinate to be on the ground
            double groundY = findGroundY(clonePositions[i][0], clonePositions[i][1], clonePositions[i][2]);
            Vec3 clonePos = new Vec3(clonePositions[i][0], groundY, clonePositions[i][2]);
            shadowClones.add(clonePos);
            
            // SERVER-SIDE particle effects for each clone for visibility
            if (serverLevel != null) {
                // Multiple particle effects for each clone
                for (int j = 0; j < 20; j++) {
                    double offsetX = (this.random.nextDouble() - 0.5) * 1.0;
                    double offsetY = this.random.nextDouble() * 2.0;
                    double offsetZ = (this.random.nextDouble() - 0.5) * 1.0;
                    
                    serverLevel.sendParticles(ParticleTypes.PORTAL, 
                        clonePos.x + offsetX, clonePos.y + offsetY, clonePos.z + offsetZ,
                        1, 0.0D, 0.0D, 0.0D, 0.0);
                    serverLevel.sendParticles(ParticleTypes.ENCHANT, 
                        clonePos.x + offsetX, clonePos.y + offsetY, clonePos.z + offsetZ,
                        1, 0.0D, 0.0D, 0.0D, 0.0);
                    serverLevel.sendParticles(ParticleTypes.WITCH, 
                        clonePos.x + offsetX, clonePos.y + offsetY, clonePos.z + offsetZ,
                        1, 0.0D, 0.0D, 0.0D, 0.0);
                }
                
                // Big explosion-like effect at clone position
                serverLevel.sendParticles(ParticleTypes.EXPLOSION, 
                    clonePos.x, clonePos.y + 1, clonePos.z,
                    3, 0.0D, 0.0D, 0.0D, 0.0);
                serverLevel.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, 
                    clonePos.x, clonePos.y + 1, clonePos.z,
                    2, 0.0D, 0.1D, 0.0D, 0.0);
                    
                // Add sonic boom effect for ultra visibility
                serverLevel.sendParticles(ParticleTypes.SONIC_BOOM, 
                    clonePos.x, clonePos.y + 1, clonePos.z,
                    1, 0.0D, 0.0D, 0.0D, 0.0);
            }
        }
        
        shadowCloneDuration = SHADOW_CLONE_DURATION;
        quadShadowCooldown = QUAD_SHADOW_COOLDOWN;
        this.playSound(SoundEvents.ILLUSIONER_MIRROR_MOVE, 1.0F, 1.0F);
        this.playSound(SoundEvents.ENDER_DRAGON_FLAP, 1.0F, 0.8F);
        this.playSound(SoundEvents.WITHER_SPAWN, 0.3F, 2.0F);
    }

    private double findGroundY(double x, double y, double z) {
        net.minecraft.core.BlockPos.MutableBlockPos mutablePos = new net.minecraft.core.BlockPos.MutableBlockPos(x, y, z);
        
        // First, find the highest solid block below us
        double highestSolidY = -1.0;
        while (mutablePos.getY() > this.level().getMinBuildHeight()) {
            net.minecraft.world.level.block.state.BlockState blockState = this.level().getBlockState(mutablePos);
            
            // Check for solid, non-air blocks that provide collision
            if (blockState.isSolid() && !blockState.getCollisionShape(this.level(), mutablePos).isEmpty()) {
                highestSolidY = mutablePos.getY();
                break; // Found the highest solid block
            }
            
            mutablePos.move(net.minecraft.core.Direction.DOWN);
        }
        
        // If we found solid ground, return Y position ABOVE the solid block (1 block up)
        if (highestSolidY >= 0) {
            return highestSolidY + 1.0;
        }
        
        // If no ground found, search upwards to find any solid block to attach to
        mutablePos = new net.minecraft.core.BlockPos.MutableBlockPos(x, y, z);
        while (mutablePos.getY() < this.level().getMaxBuildHeight()) {
            net.minecraft.world.level.block.state.BlockState blockState = this.level().getBlockState(mutablePos);
            
            // Check for solid, non-air blocks that provide collision
            if (blockState.isSolid() && !blockState.getCollisionShape(this.level(), mutablePos).isEmpty()) {
                return mutablePos.getY() + 1.0; // Position above the found block
            }
            
            mutablePos.move(net.minecraft.core.Direction.UP);
        }
        
        // Fallback: return original position if no solid blocks found (should be rare)
        return y;
    }
    
    private void teleportToShadowClone(int cloneIndex) {
        if (cloneIndex < 0 || cloneIndex >= shadowClones.size()) return;
        
        // Check if this shadow has already been used (seperti Hayabusa - tiap shadow cuma bisa sekali)
        if (usedShadowClones.contains(cloneIndex)) {
            return; // Shadow sudah dipakai, tidak bisa teleport lagi
        }
        
        Vec3 oldPos = this.position();
        Vec3 clonePos = shadowClones.get(cloneIndex);
        
        // Validate teleport destination - ensure it's not inside blocks
        if (!isValidTeleportLocation(clonePos)) {
            return; // Skip teleport if destination is invalid (inside blocks)
        }
        
        // Mark this shadow as used
        usedShadowClones.add(cloneIndex);
        
        // Force immediate teleport using multiple methods for reliability
        // Method 1: Direct position set
        this.setPos(clonePos.x, clonePos.y, clonePos.z);
        
        // Method 2: Also try teleportTo for compatibility
        this.teleportTo(clonePos.x, clonePos.y, clonePos.z);
        
        // Method 3: Reset delta movement to prevent rubber-banding
        this.setDeltaMovement(0, 0, 0);
        
        // Force position update on client side
        if (this.level() instanceof net.minecraft.server.level.ServerLevel serverLevel) {
            // Massive effects at departure position (SERVER SIDE - will sync to client)
            for (int i = 0; i < 25; i++) {
                double offsetX = (this.random.nextDouble() - 0.5) * 1.5;
                double offsetY = this.random.nextDouble() * 2.0;
                double offsetZ = (this.random.nextDouble() - 0.5) * 1.5;
                serverLevel.sendParticles(ParticleTypes.EXPLOSION, 
                    oldPos.x + offsetX, oldPos.y + offsetY, oldPos.z + offsetZ,
                    1, 0.0D, 0.0D, 0.0D, 0.0);
                serverLevel.sendParticles(ParticleTypes.LARGE_SMOKE, 
                    oldPos.x + offsetX, oldPos.y + offsetY, oldPos.z + offsetZ,
                    1, 0.0D, 0.1D, 0.0D, 0.0);
                serverLevel.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, 
                    oldPos.x + offsetX, oldPos.y + offsetY, oldPos.z + offsetZ,
                    1, 0.0D, 0.0D, 0.0D, 0.0);
            }
            
            // Massive effects at arrival position (SERVER SIDE - will sync to client)
            for (int i = 0; i < 25; i++) {
                double offsetX = (this.random.nextDouble() - 0.5) * 1.5;
                double offsetY = this.random.nextDouble() * 2.0;
                double offsetZ = (this.random.nextDouble() - 0.5) * 1.5;
                serverLevel.sendParticles(ParticleTypes.EXPLOSION, 
                    this.getX() + offsetX, this.getY() + offsetY, this.getZ() + offsetZ,
                    1, 0.0D, 0.0D, 0.0D, 0.0);
                serverLevel.sendParticles(ParticleTypes.PORTAL, 
                    this.getX() + offsetX, this.getY() + offsetY, this.getZ() + offsetZ,
                    1, 0.0D, 0.0D, 0.0D, 0.0);
                serverLevel.sendParticles(ParticleTypes.ENCHANTED_HIT, 
                    this.getX() + offsetX, this.getY() + offsetY, this.getZ() + offsetZ,
                    1, 0.0D, 0.0D, 0.0D, 0.0);
            }
        }
        
        // Multiple sound effects
        this.playSound(SoundEvents.ENDERMAN_TELEPORT, 1.0F, 1.0F);
        this.playSound(SoundEvents.ENDER_DRAGON_FLAP, 1.0F, 1.5F);
        this.playSound(SoundEvents.LIGHTNING_BOLT_IMPACT, 0.5F, 1.8F);
        
        // JANGAN clear semua clones - biarkan shadow lain masih bisa dipakai
        // Remove hanya shadow yang sudah dipakai dari list
        shadowClones.remove(cloneIndex);
        
        // Adjust indices in usedShadowClones after removal
        java.util.Set<Integer> newUsed = new java.util.HashSet<>();
        for (Integer used : usedShadowClones) {
            if (used < cloneIndex) {
                newUsed.add(used);
            } else if (used > cloneIndex) {
                newUsed.add(used - 1);
            }
        }
        usedShadowClones.clear();
        usedShadowClones.addAll(newUsed);
        
        // Jika semua shadow sudah dipakai, baru clear semua
        if (shadowClones.isEmpty()) {
            shadowCloneDuration = 0;
        }
    }
    
    /**
     * Check if a position is valid for teleportation (not inside blocks)
     * @param pos Position to check
     * @return True if position is valid (above ground, not inside blocks)
     */
    private boolean isValidTeleportLocation(Vec3 pos) {
        // Use proper BlockPos construction from integer coordinates
        net.minecraft.core.BlockPos blockPos = new net.minecraft.core.BlockPos((int)Math.floor(pos.x), (int)Math.floor(pos.y), (int)Math.floor(pos.z));
        
        // Check if the position is inside any solid blocks
        for (net.minecraft.core.Direction direction : net.minecraft.core.Direction.values()) {
            net.minecraft.core.BlockPos neighborPos = blockPos.relative(direction);
            net.minecraft.world.level.block.state.BlockState state = this.level().getBlockState(neighborPos);
            
            // If any adjacent block is solid and we're inside it, invalid
            if (state.isSolid() && neighborPos.getY() >= blockPos.getY() - 1 && neighborPos.getY() <= blockPos.getY() + 1) {
                return false;
            }
        }
        
        // Check if we're above ground (Y position is valid)
        double groundY = findGroundY(pos.x, pos.y, pos.z);
        return pos.y >= groundY;
    }
    
    private void performShadowKill(LivingEntity target) {
        if (shadowKillCooldown > 0 || target == null) return;

        // Dash to target like Hayabusa ultimate
        Vec3 targetPos = target.position();
        Vec3 direction = targetPos.subtract(this.position()).normalize();
        Vec3 dashTarget = targetPos.subtract(direction.scale(1.5)); // Stop slightly before target
        
        // Instant teleport/dash to target
        this.teleportTo(dashTarget.x, dashTarget.y, dashTarget.z);
        
        // Use Rust optimization for damage calculation
        // Base damage 30.0 with scaling from passive stacks
        double optimizedDamage = OptimizationInjector.calculateShadowKillDamage(shadowKillPassiveStacks, 30.0);
        
        // Apply massive damage immediately
        target.hurt(this.damageSources().mobAttack(this), (float) optimizedDamage);
        
        // Consume all passive stacks
        shadowKillPassiveStacks = 0;
        
        // Visual effects at both positions - ultimate should be flashy!
        this.level().addParticle(ParticleTypes.DRAGON_BREATH, target.getX(), target.getY() + 1, target.getZ(), 0.0D, 0.0D, 0.0D);
        this.level().addParticle(ParticleTypes.SWEEP_ATTACK, this.getX(), this.getY() + 1, this.getZ(), 0.0D, 0.0D, 0.0D);
        this.level().addParticle(ParticleTypes.SONIC_BOOM, this.getX(), this.getY() + 1, this.getZ(), 0.0D, 0.0D, 0.0D);
        this.level().addParticle(ParticleTypes.EXPLOSION, target.getX(), target.getY() + 1, target.getZ(), 0.0D, 0.0D, 0.0D);
        this.playSound(SoundEvents.PLAYER_ATTACK_CRIT, 1.0F, 0.5F);
        this.playSound(SoundEvents.ENDERMAN_TELEPORT, 1.0F, 1.2F);
        this.playSound(SoundEvents.LIGHTNING_BOLT_THUNDER, 0.5F, 1.5F);
        
        shadowKillCooldown = SHADOW_KILL_COOLDOWN;
    }

    private void performRangedAttack(LivingEntity target) {
        double d0 = target.getX() - this.getX();
        double d1 = target.getY(0.3333333333333333D) - this.getY();
        double d2 = target.getZ() - this.getZ();
        
        // Use Rust-optimized distance calculation
        double d3 = com.kneaf.core.RustNativeLoader.vectorLength(d0, 0.0, d2);

        // Use proper weapon for arrow firing (required by Minecraft's Arrow constructor)
        Arrow arrow = new Arrow(this.level(), this, new ItemStack(Items.BOW), new ItemStack(Items.ARROW));
        arrow.setPos(this.getX(), this.getEyeY() - 0.1D, this.getZ());
        arrow.shoot(d0, d1 + d3 * 0.2D, d2, 1.6F, 14);
        arrow.setBaseDamage(6.0D);
        if (!this.level().isClientSide()) {
            ((net.minecraft.server.level.ServerLevel)this.level()).addFreshEntity(arrow);
        }
        this.playSound(SoundEvents.SKELETON_SHOOT, 1.0F, 1.0F / (this.getRandom().nextFloat() * 0.4F + 0.8F));
    }

    // Hayabusa Goal Classes
    
    private static class PhantomShurikenGoal extends Goal {
        private final ShadowZombieNinja ninja;

        public PhantomShurikenGoal(ShadowZombieNinja ninja) {
            this.ninja = ninja;
            this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            LivingEntity target = ninja.getTarget();
            return target != null && target.isAlive() && ninja.phantomShurikenCooldown == 0 && ninja.distanceToSqr(target) > 4.0D;
        }

        @Override
        public void start() {
            // Instant cast - no charge time like Hayabusa
            LivingEntity target = ninja.getTarget();
            if (target != null) {
                ninja.getLookControl().setLookAt(target, 30.0F, 30.0F);
                ninja.performPhantomShuriken(target);
            }
            this.stop();
        }

        @Override
        public void tick() {
            // Goal completes instantly in start()
        }

        @Override
        public boolean canContinueToUse() {
            return false; // Always complete immediately
        }
    }
    
    private static class QuadShadowGoal extends Goal {
        private final ShadowZombieNinja ninja;

        public QuadShadowGoal(ShadowZombieNinja ninja) {
            this.ninja = ninja;
            this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            return ninja.getTarget() != null && ninja.quadShadowCooldown == 0;
        }

        @Override
        public void start() {
            // Only summon clones - NO teleport here
            ninja.performQuadShadow();
            this.stop();
        }

        @Override
        public void tick() {
            // Goal completes instantly in start()
        }

        @Override
        public boolean canContinueToUse() {
            return false; // Always complete immediately
        }
    }
    
    // NEW: Teleport between shadow clones (NO COOLDOWN - like Hayabusa shadow teleport)
    private static class ShadowTeleportGoal extends Goal {
        private final ShadowZombieNinja ninja;
        private int teleportDelay = 0;
        private static final int MIN_TELEPORT_DELAY = 20; // 1 second minimum between teleports
        private static final double DODGE_DISTANCE_SQR = 9.0D; // Dodge if closer than 3 blocks
        private static final double ENGAGE_DISTANCE_SQR = 64.0D; // Engage if further than 8 blocks

        public ShadowTeleportGoal(ShadowZombieNinja ninja) {
            this.ninja = ninja;
            this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            if (ninja.shadowClones.isEmpty() || ninja.getTarget() == null || !ninja.hasLineOfSight(ninja.getTarget())) {
                return false;
            }
            
            if (teleportDelay > 0) {
                teleportDelay--;
                return false;
            }
            
            // Check if there are any unused clones left
            boolean hasUnusedClones = false;
            for (int i = 0; i < ninja.shadowClones.size(); i++) {
                if (!ninja.usedShadowClones.contains(i)) {
                    hasUnusedClones = true;
                    break;
                }
            }
            if (!hasUnusedClones) {
                return false;
            }

            double distToTargetSqr = ninja.distanceToSqr(ninja.getTarget());
            // Teleport to dodge or to engage
            return distToTargetSqr < DODGE_DISTANCE_SQR || distToTargetSqr > ENGAGE_DISTANCE_SQR;
        }

        @Override
        public void start() {
            LivingEntity target = ninja.getTarget();
            if (target == null || ninja.shadowClones.isEmpty()) {
                this.stop();
                return;
            }

            double distToTargetSqr = ninja.distanceToSqr(target);
            int bestCloneIndex = -1;

            if (distToTargetSqr < DODGE_DISTANCE_SQR) {
                // --- DODGE LOGIC ---
                // Ninja is too close, find the FARTHEST available clone to teleport to.
                double maxDist = -1.0;
                for (int i = 0; i < ninja.shadowClones.size(); i++) {
                    if (ninja.usedShadowClones.contains(i)) continue;

                    Vec3 clonePos = ninja.shadowClones.get(i);
                    double cloneToTargetDistSqr = clonePos.distanceToSqr(target.position());
                    if (cloneToTargetDistSqr > maxDist) {
                        maxDist = cloneToTargetDistSqr;
                        bestCloneIndex = i;
                    }
                }
            } else {
                // --- ENGAGE LOGIC ---
                // Ninja is too far, find the CLOSEST available clone to the target.
                double minDist = Double.MAX_VALUE;
                for (int i = 0; i < ninja.shadowClones.size(); i++) {
                    if (ninja.usedShadowClones.contains(i)) continue;

                    Vec3 clonePos = ninja.shadowClones.get(i);
                    double cloneToTargetDistSqr = clonePos.distanceToSqr(target.position());
                    if (cloneToTargetDistSqr < minDist) {
                        minDist = cloneToTargetDistSqr;
                        bestCloneIndex = i;
                    }
                }
            }

            // Final validation: ensure the selected clone position is valid (not inside blocks)
            if (bestCloneIndex != -1 && ninja.shadowClones.size() > bestCloneIndex) {
                Vec3 clonePos = ninja.shadowClones.get(bestCloneIndex);
                if (!ninja.isValidTeleportLocation(clonePos)) {
                    // If the best clone is invalid, find the next best valid clone
                    bestCloneIndex = -1;
                    for (int i = 0; i < ninja.shadowClones.size(); i++) {
                        if (ninja.usedShadowClones.contains(i)) continue;

                        Vec3 checkPos = ninja.shadowClones.get(i);
                        if (ninja.isValidTeleportLocation(checkPos)) {
                            bestCloneIndex = i;
                            break;
                        }
                    }
                }
            }

            if (bestCloneIndex != -1) {
                ninja.teleportToShadowClone(bestCloneIndex);
                teleportDelay = MIN_TELEPORT_DELAY; // Reset delay
            }
            
            this.stop();
        }

        @Override
        public boolean canContinueToUse() {
            return false; // Goal completes instantly
        }
    }
    
    private static class ShadowKillGoal extends Goal {
        private final ShadowZombieNinja ninja;

        public ShadowKillGoal(ShadowZombieNinja ninja) {
            this.ninja = ninja;
            this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            LivingEntity target = ninja.getTarget();
            return target != null && target.isAlive() && ninja.shadowKillCooldown == 0 &&
                   ninja.distanceToSqr(target) <= 16.0D && ninja.shadowKillPassiveStacks >= 2;
        }

        @Override
        public void start() {
            // Instant execution like Hayabusa ultimate - dash and execute immediately
            LivingEntity target = ninja.getTarget();
            if (target != null) {
                ninja.getLookControl().setLookAt(target, 30.0F, 30.0F);
                ninja.performShadowKill(target);
            }
            this.stop();
        }

        @Override
        public void tick() {
            // Goal completes instantly in start()
        }

        @Override
        public boolean canContinueToUse() {
            return false; // Always complete immediately
        }
    }

    private static class RangedAttackGoal extends Goal {
        private final ShadowZombieNinja ninja;
        private int attackTimer = 0;
        private int attackCooldown = 0;

        public RangedAttackGoal(ShadowZombieNinja ninja) {
            this.ninja = ninja;
            this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            LivingEntity target = ninja.getTarget();
            return target != null && target.isAlive() && ninja.distanceToSqr(target) > 9.0D && attackCooldown <= 0;
        }

        @Override
        public void start() {
            this.attackTimer = 20; // 1 second charge time
        }

        @Override
        public void tick() {
            if (attackCooldown > 0) {
                attackCooldown--;
            }
            LivingEntity target = ninja.getTarget();
            if (target != null) {
                ninja.getLookControl().setLookAt(target, 30.0F, 30.0F);
                if (--this.attackTimer <= 0) {
                    ninja.performRangedAttack(target);
                    this.attackCooldown = 60;
                    this.stop();
                }
            }
        }

        @Override
        public void stop() {
            this.attackTimer = 0;
        }

        @Override
        public boolean canContinueToUse() {
            return this.attackTimer > 0;
        }

    }
}