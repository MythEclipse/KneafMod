package com.kneaf.entities;
import com.kneaf.core.OptimizationInjector;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.BossEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.resources.ResourceLocation;
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
    private long lastQuadShadowUse = 0;
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
    private static final int PHANTOM_SHURIKEN_COOLDOWN = 40; // 2 seconds - Skill 1 (faster for close combat)
    private static final int PHANTOM_SHURIKEN_COUNT = 3; // 3 shurikens like boomerang
    private static final double PHANTOM_SHURIKEN_MAX_RANGE = 3.0D; // Max 3 blocks range
    private static final int QUAD_SHADOW_COOLDOWN = 360; // 18 seconds - Skill 2 (diperpanjang)
    private static final int SHADOW_KILL_COOLDOWN = 600; // 30 seconds - Ultimate
    private static final int MAX_PASSIVE_STACKS = 4;
    private static final float PASSIVE_DAMAGE_MULTIPLIER = 1.2f;
     
    // Shadow clones for Quad Shadow skill
    private final java.util.List<Vec3> shadowClones = new java.util.ArrayList<>();
    private final java.util.Set<Integer> usedShadowClones = new java.util.HashSet<>(); // Track which shadows have been used
    private final java.util.Map<Integer, Vec3> activeClones = new java.util.HashMap<>(); // Track active clone positions and status
    private final java.util.Map<Integer, Integer> cloneSkillCooldowns = new java.util.HashMap<>(); // Track skill cooldowns per clone
    private int shadowCloneDuration = 0;
    private static final int SHADOW_CLONE_DURATION = 200; // 10 seconds
    private static final double SHADOW_CLONE_DISTANCE = 10.0; // 10 blocks dari tengah (seperti Hayabusa)
    private static final int MAX_COORDINATED_CLONES = 3; // Maximum clones that can act in coordination
    private static final double CLONE_COORDINATION_RADIUS = 15.0; // Maximum distance for clone coordination
    private static final int CLONE_SKILL_COOLDOWN = 60; // 3 seconds cooldown per clone skill use

    public ShadowZombieNinja(EntityType<? extends Zombie> entityType, Level level) {
        super(entityType, level);
    }

    // AI Configuration Constants - ENHANCED
    private static final double TARGET_PRIORITY_DISTANCE_WEIGHT = 0.25;    // Less weight on distance, more on other factors
    private static final double TARGET_PRIORITY_THREAT_WEIGHT = 0.35;    // Balanced threat assessment
    private static final double TARGET_PRIORITY_RECENT_ATTACK_WEIGHT = 0.20; // Recent attacks
    private static final double TARGET_PRIORITY_PATTERN_RECOGNITION_WEIGHT = 0.20; // New: Pattern recognition
    private static final double TARGET_PRIORITY_ENVIRONMENTAL_ADVANTAGE_WEIGHT = 0.20; // New: Environmental factors
    private static final int TARGET_SELECTION_RADIUS = 60; // Extended detection range (from 40 to 60)
    private static final int MAX_TARGET_MEMORY = 10; // Remember last 10 targets (from 5 to 10)
    private static final int PATTERN_RECOGNITION_WINDOW = 60; // 3 seconds of pattern data (from 1 to 3)
    private static final int PREDICTION_HORIZON = 10; // Predict 0.5 seconds into future (20 ticks = 1 second)

    // Enhanced AI State Tracking - EXPANDED
    private final java.util.Queue<LivingEntity> targetHistory = new java.util.LinkedList<>();
    private final java.util.Map<LivingEntity, java.util.List<Long>> attackPatterns = new java.util.HashMap<>();
    private final java.util.Map<LivingEntity, java.util.List<Vec3>> movementPatterns = new java.util.HashMap<>();
    private LivingEntity currentHighPriorityTarget = null;
    private LivingEntity secondaryTarget = null;
    private int patternRecognitionCounter = 0;
    private Vec3 lastKnownTargetPosition = Vec3.ZERO;
    private Vec3 previousTargetPosition = Vec3.ZERO;
    private long lastTargetSeenTime = 0;
    private boolean isTargetMovingPredictably = false;
    private Vec3 predictedTargetPosition = Vec3.ZERO;
    private int consecutivePredictions = 0;
    private double predictionAccuracy = 0.5;
    private boolean isTargetChangingPattern = false;

    // Add missing helper methods
    public boolean isSolidBlock(net.minecraft.core.BlockPos pos) {
        net.minecraft.world.level.block.state.BlockState state = this.level().getBlockState(pos);
        return state.isSolidRender(level(), pos) && !state.isAir() && !state.getFluidState().isSource();
    }

    public int findClearRadius(double radius) {
        int clearCount = 0;
        Vec3 pos = this.position();
        
        for (double x = -radius; x <= radius; x += 0.5) {
            for (double z = -radius; z <= radius; z += 0.5) {
                for (double y = -1; y <= 1; y += 0.5) { // Check small vertical range
                    Vec3 checkPos = pos.add(x, y, z);
                    if (isBlockClear(checkPos)) {
                        clearCount++;
                    }
                }
            }
        }
        
        return clearCount;
    }

    public boolean isBlockClear(Vec3 pos) {
        net.minecraft.core.BlockPos blockPos = new net.minecraft.core.BlockPos(
            (int)Math.floor(pos.x),
            (int)Math.floor(pos.y),
            (int)Math.floor(pos.z)
        );
        net.minecraft.world.level.block.state.BlockState state = this.level().getBlockState(blockPos);
        
        return !state.isSolidRender(level(), blockPos) && !state.getFluidState().isSource();
    }

    public boolean isInForest() {
        // Check for tree cover above
        int treeCount = 0;
        for (int x = -3; x <= 3; x += 2) {
            for (int z = -3; z <= 3; z += 2) {
                net.minecraft.core.BlockPos pos = new net.minecraft.core.BlockPos(
                    (int)Math.floor(this.getX() + x),
                    (int)Math.floor(this.getY() + 1),
                    (int)Math.floor(this.getZ() + z)
                );
                if (this.level().getBlockState(pos).getBlock() instanceof net.minecraft.world.level.block.LeavesBlock) {
                    treeCount++;
                }
            }
        }
        return treeCount > 4;
    }

    public boolean isInCave() {
        // Check for low light level and solid blocks around
        return this.level().getBrightness(net.minecraft.world.level.LightLayer.SKY, this.blockPosition()) < 8 &&
               this.findClearRadius(3.0) < 10;
    }

    @Override
    protected void registerGoals() {
        // Basic goal registration - enhanced AI goals are implemented later in the class
        this.goalSelector.addGoal(1, new ShadowKillGoal(this)); // Prioritize ultimate when conditions are right
        this.goalSelector.addGoal(2, new PhantomShurikenGoal(this)); // Smart projectile usage
        this.goalSelector.addGoal(3, new QuadShadowGoal(this)); // Tactical clone deployment
        this.goalSelector.addGoal(4, new IntelligentShadowTeleportGoal(this)); // Context-aware teleportation
        this.goalSelector.addGoal(5, new MeleeAttackGoal(this, 1.2D, false)); // Smarter melee
        this.goalSelector.addGoal(6, new MoveTowardsTargetGoal(this, 1.2D, 32.0F)); // Dynamic movement
        this.goalSelector.addGoal(7, new RandomStrollGoal(this, 1.0D)); // Terrain awareness
        this.goalSelector.addGoal(8, new RandomLookAroundGoal(this));

        this.targetSelector.addGoal(1, new NearestAttackableTargetGoal<>(this, LivingEntity.class, true)); // Enhanced target selection
        this.targetSelector.addGoal(2, new HurtByTargetGoal(this));
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
        
        // Update target prediction and pattern recognition
        updateTargetPrediction();
        updatePatternRecognition();
          
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
    
    /**
     * Update target prediction based on movement patterns
     */
    private void updateTargetPrediction() {
        LivingEntity target = this.getTarget();
        if (target == null) return;
        
        // Update position history
        previousTargetPosition = lastKnownTargetPosition;
        lastKnownTargetPosition = target.position();
        
        // Calculate movement vector
        Vec3 movementVector = lastKnownTargetPosition.subtract(previousTargetPosition);
        double movementSpeed = movementVector.length();
        
        // Update prediction
        if (movementSpeed > 0.01) {
            isTargetMovingPredictably = true;
            consecutivePredictions++;
            
            // Improve prediction accuracy based on consecutive predictions
            predictionAccuracy = Math.min(predictionAccuracy + 0.1, 0.9);
            
            // Predict future position (look 0.5-2 seconds ahead)
            double predictionTime = Math.max(0.5, Math.min(2.0, 3.0 / movementSpeed));
            predictedTargetPosition = lastKnownTargetPosition.add(movementVector.scale(predictionTime));
        } else {
            isTargetMovingPredictably = false;
            consecutivePredictions = 0;
            predictionAccuracy = 0.5;
            predictedTargetPosition = lastKnownTargetPosition;
        }
        
        // Check for pattern changes
        if (previousTargetPosition != Vec3.ZERO && lastKnownTargetPosition != Vec3.ZERO) {
            Vec3 oldDirection = previousTargetPosition.subtract(lastKnownTargetPosition).normalize();
            Vec3 newDirection = lastKnownTargetPosition.subtract(previousTargetPosition).normalize();
            double directionChange = 1.0 - oldDirection.dot(newDirection);
            
            isTargetChangingPattern = directionChange > 0.3;
        }
    }
    
    /**
     * Update pattern recognition for target behavior analysis
     */
    private void updatePatternRecognition() {
        LivingEntity target = this.getTarget();
        if (target == null) return;
        
        // Update attack pattern recognition
        attackPatterns.computeIfAbsent(target, k -> new java.util.ArrayList<Long>()).add((long)this.tickCount);
        
        // Update movement pattern recognition
        movementPatterns.computeIfAbsent(target, k -> new java.util.ArrayList<Vec3>()).add(target.position());
        
        // Limit pattern memory to recent data
        if (patternRecognitionCounter++ >= PATTERN_RECOGNITION_WINDOW) {
            patternRecognitionCounter = 0;
            
            // Clean up old attack patterns
            for (LivingEntity t : new java.util.ArrayList<>(attackPatterns.keySet())) {
                java.util.List<Long> timestamps = attackPatterns.get(t);
                timestamps.removeIf(timestamp -> this.tickCount - timestamp > 200); // Remove attacks older than 10 seconds
                if (timestamps.isEmpty()) {
                    attackPatterns.remove(t);
                }
            }
            
            // Clean up old movement patterns
            for (LivingEntity t : new java.util.ArrayList<>(movementPatterns.keySet())) {
                java.util.List<Vec3> positions = movementPatterns.get(t);
                if (positions.size() > 20) { // Keep only last 20 positions
                    positions.remove(0);
                }
            }
        }
    }
    
    /**
     * Get prediction accuracy for target movement
     */
    public double getPredictionAccuracy() {
        return predictionAccuracy;
    }
    
    /**
     * Check if target is changing movement pattern
     */
    public boolean isTargetChangingPattern() {
        return isTargetChangingPattern;
    }
    
    /**
     * Get secondary target if available
     */
    public LivingEntity getSecondaryTarget() {
        return secondaryTarget;
    }
    
    /**
     * Set secondary target for focused attacks
     */
    public void setSecondaryTarget(LivingEntity target) {
        this.secondaryTarget = target;
    }

    @Override
    public boolean doHurtTarget(@Nonnull Entity target) {
        boolean flag = super.doHurtTarget(target);
        if (flag && target instanceof LivingEntity livingTarget) {
            // Hayabusa passive: Shadow Kill enhanced attacks
            // Each stack increases damage by 15%
            float baseDamage = 4.0F; // Reduced from 6.0F for better balance
            float passiveMultiplier = 1.0f + (shadowKillPassiveStacks * 0.15f);
            float totalDamage = baseDamage * passiveMultiplier;
            
            livingTarget.hurt(this.damageSources().mobAttack(this), totalDamage * 0.7f); // Reduce damage to 70% for balance
            
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

       // Calculate spread angles for 3 shurikens (boomerang formation)
       double[] angles = {0.0, 45.0, -45.0}; // Main + 2 spread shurikens
       double targetDistance = Math.sqrt(this.distanceToSqr(target));

       for (int i = 0; i < PHANTOM_SHURIKEN_COUNT; i++) {
           createBoomerangShuriken(target, targetPos, currentPos, angles[i], targetDistance);
       }
       
       // Enhanced visual effects for shuriken throw
       for (int i = 0; i < 15; i++) {
           double offsetX = (this.random.nextDouble() - 0.5) * 0.5;
           double offsetY = (this.random.nextDouble() - 0.5) * 0.5;
           double offsetZ = (this.random.nextDouble() - 0.5) * 0.5;
           this.level().addParticle(ParticleTypes.SONIC_BOOM,
               this.getX() + offsetX, this.getY() + 1 + offsetY, this.getZ() + offsetZ,
               0.0D, 0.0D, 0.0D);
           this.level().addParticle(ParticleTypes.SWEEP_ATTACK,
               this.getX() + offsetX, this.getY() + 1 + offsetY, this.getZ() + offsetZ,
               0.0D, 0.0D, 0.0D);
       }
       
       this.playSound(SoundEvents.PLAYER_ATTACK_SWEEP, 1.0F, 1.5F);
       this.playSound(SoundEvents.ENDER_DRAGON_FLAP, 1.0F, 1.8F);

       phantomShurikenCooldown = PHANTOM_SHURIKEN_COOLDOWN;
   }
   
    /**
     * Create an enhanced boomerang-style shuriken with improved physics and targeting
     * Features: Smart trajectory, predictive return, area damage on return, and visual effects
     */
    private void createBoomerangShuriken(LivingEntity target, Vec3 targetPos, Vec3 currentPos, double angleOffset, double targetDistance) {
       // Enhanced spread calculation with more natural variation (±15° from base angle)
       double baseAngle = Math.toRadians(angleOffset);
       double randomSpread = this.random.nextGaussian() * 0.2618; // ±15° in radians
       double angle = baseAngle + randomSpread;
       
       // Calculate spread with better distribution for closer combat
       double spreadFactor = Math.min(0.5D, targetDistance / 5.0D); // More spread at longer ranges
       double spreadX = Math.sin(angle) * spreadFactor;
       double spreadZ = Math.cos(angle) * spreadFactor;

       // Improved targeting vector with predictive adjustment
       double d0 = targetPos.x - currentPos.x + spreadX;
       double d1 = targetPos.y + target.getEyeHeight() * 0.3 - (currentPos.y + this.getEyeHeight() * 0.7);
       double d2 = targetPos.z - currentPos.z + spreadZ;
       
       // Normalize with more precise control for close-range combat
       double length = Math.sqrt(d0 * d0 + d2 * d2);
       if (length > 0.01) {
           // Adjust velocity based on distance - faster for closer targets
           double speedFactor = Math.min(2.5D, 1.0D + (5.0D - targetDistance) * 0.5D);
           d0 = (d0 / length) * speedFactor;
           d2 = (d2 / length) * speedFactor;
       }

       // Create enhanced shuriken with better visibility and physics
       Arrow shuriken = new Arrow(this.level(), this, new ItemStack(Items.NETHERITE_SCRAP), new ItemStack(Items.IRON_SWORD)) {
           private final LivingEntity originalTarget = target;
           private final Vec3 startPos = currentPos;
           private boolean hasHitPrimaryTarget = false;
           private int returnPhase = 0; // 0 = active, 1 = returning, 2 = expired
           private Vec3 returnTrajectory = Vec3.ZERO;

           @Override
           public boolean hurt(@Nonnull DamageSource source, float amount) {
               if (source.getEntity() == originalTarget && !hasHitPrimaryTarget) {
                   hasHitPrimaryTarget = true;
                   returnPhase = 1;
                   
                   // Initial hit - deal heavy damage
                   this.setBaseDamage(12.0D);
                   originalTarget.hurt(this.damageSources().mobAttack(ShadowZombieNinja.this), 12.0F);
                   
                   // Play impact sound
                   this.playSound(SoundEvents.PLAYER_ATTACK_SWEEP, 1.0F, 1.2F);
                   
                   // Calculate intelligent return path (avoid obstacles when possible)
                   Vec3 toNinja = startPos.subtract(this.position()).normalize();
                   Vec3 adjustedReturn = toNinja.scale(0.6D).add(new Vec3(
                       this.random.nextGaussian() * 0.1,
                       0.2D,
                       this.random.nextGaussian() * 0.1
                   ));
                   
                   returnTrajectory = adjustedReturn;
                   
                   // Initialize return physics
                   this.setNoGravity(false);
                   this.setBaseDamage(9.0D); // Slightly less damage on return but still effective
                   this.shoot(returnTrajectory.x, returnTrajectory.y, returnTrajectory.z, 2.0F, 0.1F);
                   
                   // Visual effect for hit
                   this.level().addParticle(ParticleTypes.CRIT, this.getX(), this.getY(), this.getZ(), 0.0D, 0.0D, 0.0D);
                   
                   return true;
               }
               return super.hurt(source, amount);
           }

           @Override
           public void tick() {
               super.tick();
               
               // Phase 1: Active flight - check for return conditions
               if (returnPhase == 0 && this.tickCount > 40) { // Shorter active time (2 seconds)
                   startReturnTrajectory();
               }
               
               // Phase 2: Returning to ninja - maintain trajectory
               if (returnPhase == 1) {
                   if (this.tickCount % 5 == 0) { // Adjust trajectory periodically
                       this.shoot(returnTrajectory.x, returnTrajectory.y + 0.05D, returnTrajectory.z, 2.0F, 0.0F);
                   }
                   
                   // Check if we're close enough to expire
                   if (this.distanceToSqr(startPos) < 1.0D) {
                       expireShuriken();
                   }
               }
               
               // Phase 3: Expired - clean up
               if (returnPhase == 2) {
                   this.remove(Entity.RemovalReason.DISCARDED);
               }
           }
           
           /**
            * Start return trajectory to ninja with smart path adjustment
            */
           private void startReturnTrajectory() {
               returnPhase = 1;
               
               // Calculate optimal return path considering terrain
               Vec3 toNinja = startPos.subtract(this.position()).normalize();
               Vec3 heightAdjustment = new Vec3(0, Math.max(0.1D, this.position().y - startPos.y) * 0.1D, 0);
               returnTrajectory = toNinja.add(heightAdjustment).scale(0.7D);
               
               this.setNoGravity(false);
               this.setBaseDamage(9.0D);
               this.shoot(returnTrajectory.x, returnTrajectory.y, returnTrajectory.z, 2.0F, 0.0F);
               
               // Visual indicator that shuriken is returning
               this.level().addParticle(ParticleTypes.ENCHANTED_HIT, this.getX(), this.getY(), this.getZ(), 0.0D, 0.0D, 0.0D);
           }
           
           /**
            * Handle shuriken expiration with area effect
            */
           private void expireShuriken() {
               returnPhase = 2;
               
               // Deal area damage on return - bigger radius than initial hit
               if (this.level() instanceof ServerLevel serverLevel) {
                   serverLevel.getEntitiesOfClass(LivingEntity.class,
                       new net.minecraft.world.phys.AABB(
                           this.position().x - 1.5D, this.position().y - 1.0D, this.position().z - 1.5D,
                           this.position().x + 1.5D, this.position().y + 1.0D, this.position().z + 1.5D
                       ))
                       .forEach(entity -> {
                           if (entity != ShadowZombieNinja.this && entity.distanceToSqr(ShadowZombieNinja.this) < 6.0D) {
                               float returnDamage = 9.0F;
                               // Bonus damage to original target
                               if (entity == originalTarget) {
                                   returnDamage *= 1.2F;
                               }
                               entity.hurt(this.damageSources().mobAttack(ShadowZombieNinja.this), returnDamage);
                           }
                       });
                   
                   // Play return impact sound
                   this.playSound(SoundEvents.PLAYER_ATTACK_CRIT, 1.0F, 0.8F);
                   
                   // Create explosion-like effect for return
                   serverLevel.sendParticles(ParticleTypes.EXPLOSION, this.getX(), this.getY(), this.getZ(), 3, 0.2D, 0.2D, 0.2D, 0.0D);
                   serverLevel.sendParticles(ParticleTypes.SMOKE, this.getX(), this.getY(), this.getZ(), 5, 0.3D, 0.3D, 0.3D, 0.0D);
               }
               
               this.remove(Entity.RemovalReason.DISCARDED);
           }
       };

       // Configure shuriken for better performance and visibility
       shuriken.setPos(this.getX(), this.getEyeY() - 0.2D, this.getZ());
       shuriken.shoot(d0, d1 + length * 0.2D, d2, 2.3F, 0.0F);
       shuriken.setBaseDamage(12.0D);
       shuriken.setNoGravity(true);
       shuriken.pickup = net.minecraft.world.entity.projectile.AbstractArrow.Pickup.DISALLOWED;
       
       // Add glow effect for better visibility in combat
       shuriken.addTag("kneaf:glowing_shuriken");
       
       if (!this.level().isClientSide()) {
           ((ServerLevel)this.level()).addFreshEntity(shuriken);
       }

       // Enhanced trail particles with better distribution
       for (int i = 0; i < 5; i++) { // More particles for better visibility
           double progress = i / 5.0;
           double offsetX = this.random.nextGaussian() * 0.1;
           double offsetY = this.random.nextGaussian() * 0.1;
           double offsetZ = this.random.nextGaussian() * 0.1;
           
           double particleX = this.getX() + d0 * progress * 0.4 + offsetX;
           double particleY = this.getEyeY() + d1 * progress * 0.4 + offsetY;
           double particleZ = this.getZ() + d2 * progress * 0.4 + offsetZ;
           
           this.level().addParticle(ParticleTypes.CRIT, particleX, particleY, particleZ, 0.0D, 0.0D, 0.0D);
           this.level().addParticle(ParticleTypes.ENCHANTED_HIT, particleX, particleY, particleZ, 0.0D, 0.0D, 0.0D);
       }
       
       // Play throw sound
       this.playSound(SoundEvents.PLAYER_ATTACK_SWEEP, 1.0F, 1.3F);
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
        
        // DEBUG: Log clone positions
        if (serverLevel != null) {
            serverLevel.getServer().getPlayerList().getPlayers().forEach(player -> {
            });
        }
        
        for (int i = 0; i < 4; i++) {
            // Adjust Y-coordinate to be on the ground
            double groundY = findGroundY(clonePositions[i][0], clonePositions[i][1], clonePositions[i][2]);
            Vec3 clonePos = new Vec3(clonePositions[i][0], groundY, clonePositions[i][2]);
            shadowClones.add(clonePos);
            
            // DEBUG: Log individual clone positions
            if (serverLevel != null) {
                final int cloneIndex = i; // Create final copy for lambda
                serverLevel.getServer().getPlayerList().getPlayers().forEach(player -> {
                });
            }
            
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
        
        // DEBUG: Log final shadow clones count
        if (serverLevel != null) {
            serverLevel.getServer().getPlayerList().getPlayers().forEach(player -> {
            });
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
            if (blockState.isSolidRender(this.level(), mutablePos) && !blockState.getCollisionShape(this.level(), mutablePos).isEmpty()) {
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
            if (blockState.isSolidRender(this.level(), mutablePos) && !blockState.getCollisionShape(this.level(), mutablePos).isEmpty()) {
                return mutablePos.getY() + 1.0; // Position above the found block
            }
            
            mutablePos.move(net.minecraft.core.Direction.UP);
        }
        
        // Fallback: return original position if no solid blocks found (should be rare)
        return y;
    }

    /**
     * Calculate enhanced tactical score for shadow clone positions with multi-criteria evaluation
     */
    private double calculateEnhancedCloneScore(Vec3 clonePos, Vec3 targetPos, Vec3 toTarget, Vec3 ninjaPos, double currentDistSqr, LivingEntity target) {
        double score = 0.0;

        // 1. Distance factor: balance between attack range and safety (enhanced)
        double cloneToTargetDistSqr = clonePos.distanceToSqr(targetPos);
        double distanceScore = 0.0;

        if (currentDistSqr < IntelligentShadowTeleportGoal.DODGE_DISTANCE_SQR) {
            // Dodge situation: reward clones that are FARTHER from target with exponential decay
            distanceScore = 2000.0 / Math.pow(cloneToTargetDistSqr + 1.0, 1.2); // Exponential decay
            score += distanceScore * 0.35; // 35% weight (more important for dodging)
        } else if (currentDistSqr > IntelligentShadowTeleportGoal.ENGAGE_DISTANCE_SQR) {
            // Engage situation: reward clones that are CLOSER to target with logarithmic scaling
            distanceScore = 1.0 - Math.log(cloneToTargetDistSqr + 1.0) / Math.log(currentDistSqr * 2.0 + 1.0);
            score += distanceScore * 0.35; // 35% weight
        } else {
            // Ideal range: reward clones that keep us in optimal attack position
            double idealDist = Math.sqrt(IntelligentShadowTeleportGoal.DODGE_DISTANCE_SQR) + 3.0; // Slightly extended ideal range
            double distDiff = Math.abs(Math.sqrt(cloneToTargetDistSqr) - idealDist);
            distanceScore = 1.0 - Math.min(distDiff / idealDist, 1.0); // Cap at 0-1
            score += distanceScore * 0.25; // 25% weight
        }

        // 2. Line of Sight (LOS) factor: critical for both attack and evasion (enhanced)
        boolean hasLOS = isLineOfSightClear(clonePos, targetPos);
        if (hasLOS) {
            score += 300.0; // Big bonus for maintaining LOS
            score += calculateLOSQualityBonus(clonePos, targetPos); // Additional bonus for better LOS quality
        } else {
            score -= 150.0; // Penalty for losing LOS
        }

        // 3. Angle factor: reward positions with better attack angles (enhanced)
        Vec3 fromCloneToTarget = targetPos.subtract(clonePos).normalize();
        double angleDot = fromCloneToTarget.dot(toTarget);
        
        if (angleDot > IntelligentShadowTeleportGoal.SAFE_ANGLE_THRESHOLD) {
            score += 250.0; // Excellent attack position
            score += calculateAttackAngleBonus(angleDot); // Bonus for perfect angles
        } else if (angleDot > IntelligentShadowTeleportGoal.ATTACK_ANGLE_THRESHOLD) {
            score += 120.0; // Good attack position
        } else {
            score -= 75.0; // Bad angle - penalty
        }

        // 4. Cover factor: reward positions with natural cover (enhanced)
        score += calculateEnhancedCoverScore(clonePos) * 1.5; // 50% bonus for better cover detection

        // 5. Height advantage: reward higher positions (if safe) with dynamic scaling
        double heightAdvantage = clonePos.y - ninjaPos.y;
        if (heightAdvantage > 0) {
            score += heightAdvantage * 15.0; // Increased bonus for elevation
            score += calculateHeightAdvantageBonus(heightAdvantage, clonePos, targetPos); // Additional height bonus
        }

        // 6. Counter-attack bonus: reward positions where target is vulnerable
        if (isCounterAttackPosition(clonePos, target)) {
            score += IntelligentShadowTeleportGoal.COUNTER_ATTACK_BONUS;
        }

        // 7. Flanking bonus: reward positions that allow flanking attacks
        if (isFlankingPosition(clonePos, targetPos, toTarget)) {
            score += IntelligentShadowTeleportGoal.FLANKING_BONUS;
        }

        // 8. Environmental bonus: reward positions that utilize terrain advantages
        score += calculateEnvironmentalBonus(clonePos);

        // 9. Coordination bonus: reward clones that work well with other active clones
        score += calculateCoordinationBonus(clonePos, targetPos);
        score += calculateFormationBonus(clonePos, targetPos);

        return score;
    }

    /**
     * Calculate simple scoring for shadow clone positions (basic distance + LOS)
     */
    private double calculateSimpleCloneScore(Vec3 clonePos, Vec3 targetPos, Vec3 ninjaPos, double currentDistSqr) {
        double score = 0.0;
        
        // 1. Distance factor: balance between attack range and safety
        double cloneToTargetDistSqr = clonePos.distanceToSqr(targetPos);
        if (currentDistSqr < IntelligentShadowTeleportGoal.DODGE_DISTANCE_SQR) {
            // Dodge situation: reward clones that are farther from target
            score += 2000.0 / Math.pow(cloneToTargetDistSqr + 1.0, 1.2);
        } else {
            // Engage situation: reward clones that are closer to target
            score += 1.0 - Math.log(cloneToTargetDistSqr + 1.0) / Math.log(currentDistSqr * 2.0 + 1.0);
        }

        // 2. Line of Sight factor: critical for both attack and evasion
        if (isLineOfSightClear(clonePos, targetPos)) {
            score += 300.0; // Big bonus for maintaining LOS
        } else {
            score -= 150.0; // Penalty for losing LOS
        }

        // 3. Height advantage: reward higher positions
        double heightAdvantage = clonePos.y - ninjaPos.y;
        if (heightAdvantage > 0) {
            score += heightAdvantage * 10.0;
        }

        return score;
    }

    /**
     * Calculate bonus for high-quality line of sight
     */
    private double calculateLOSQualityBonus(Vec3 clonePos, Vec3 targetPos) {
        // Better LOS when:
        // 1. Line is longer (more visibility)
        // 2. No obstacles between clone and target
        // 3. Higher elevation difference
        
        double distance = clonePos.distanceTo(targetPos);
        boolean clearLOS = isLineOfSightClear(clonePos, targetPos);
        double heightDiff = Math.abs(clonePos.y - targetPos.y);
        
        double losQuality = 0.0;
        
        if (clearLOS) {
            losQuality += 50.0 * Math.min(distance / 20.0, 1.0); // Bonus based on distance (capped at 50)
            losQuality += heightDiff * 10.0; // Bonus for height advantage
        }
        
        return Math.min(losQuality, 100.0); // Cap bonus at 100
    }

    /**
     * Calculate bonus for excellent attack angles
     */
    private double calculateAttackAngleBonus(double angleDot) {
        // Perfect angles get bigger bonuses
        if (angleDot > 0.95) return 100.0;
        if (angleDot > 0.90) return 75.0;
        if (angleDot > 0.85) return 50.0;
        return 0.0;
    }

    /**
     * Enhanced cover score calculation with better obstacle detection
     */
    private double calculateEnhancedCoverScore(Vec3 pos) {
        net.minecraft.core.BlockPos blockPos = new net.minecraft.core.BlockPos(
            (int)Math.floor(pos.x),
            (int)Math.floor(pos.y),
            (int)Math.floor(pos.z)
        );

        int coverBlocks = 0;
        double coverQuality = 0.0;
        
        // Check for cover in directions that would block attacks from different angles
        net.minecraft.core.Direction[] coverDirections = {
            net.minecraft.core.Direction.NORTH,
            net.minecraft.core.Direction.SOUTH,
            net.minecraft.core.Direction.EAST,
            net.minecraft.core.Direction.WEST,
            net.minecraft.core.Direction.UP,
            net.minecraft.core.Direction.DOWN
        };

        for (net.minecraft.core.Direction dir : coverDirections) {
            net.minecraft.core.BlockPos checkPos = blockPos.relative(dir);
            net.minecraft.world.level.block.state.BlockState state = this.level().getBlockState(checkPos);
            
            // Count solid blocks as cover with quality scoring
            if (state.isSolidRender(this.level(), blockPos) && !state.isAir()) {
                coverBlocks++;
                
                // Higher quality cover from thicker blocks
                if (state.isSolidRender(this.level(), blockPos)) {
                    coverQuality += 2.0;
                } else {
                    coverQuality += 1.0;
                }
            }
        }

        // Also check for environmental cover (trees, rocks, etc.)
        coverQuality += calculateEnvironmentalCoverBonus(pos);

        // Return score based on number of cover blocks and quality (0-200)
        return (coverBlocks * 25.0) + coverQuality;
    }

    /**
     * Calculate bonus for height advantage positions
     */
    private double calculateHeightAdvantageBonus(double heightAdvantage, Vec3 clonePos, Vec3 targetPos) {
        // Better bonus when:
        // 1. Higher elevation
        // 2. Target is below us
        // 3. Clear line of sight from height
        
        double bonus = 0.0;
        
        if (clonePos.y > targetPos.y) {
            bonus += heightAdvantage * 10.0; // Base height bonus
            
            if (isLineOfSightClear(clonePos, targetPos)) {
                bonus += 50.0; // Bonus for clear LOS from height
            }
        }
        
        return Math.min(bonus, 100.0); // Cap bonus at 100
    }

    /**
     * Check if position is good for counter-attacks
     */
    private boolean isCounterAttackPosition(Vec3 clonePos, LivingEntity target) {
        Vec3 targetPos = target.position();
        
        // Good counter-attack positions are:
        // 1. Where target can't easily hit back
        // 2. Where we have clear LOS but target doesn't have clear LOS back
        // 3. Where target is vulnerable to our skills
        
        boolean targetCantSeeUs = !isLineOfSightClear(clonePos, targetPos);
        boolean weCanSeeTarget = isLineOfSightClear(clonePos, targetPos);
        boolean targetIsVulnerable = target.getHealth() < target.getMaxHealth() * 0.4;
        
        return targetCantSeeUs && weCanSeeTarget && targetIsVulnerable;
    }

    /**
     * Check if position is good for flanking attacks
     */
    private boolean isFlankingPosition(Vec3 clonePos, Vec3 targetPos, Vec3 toTarget) {
        Vec3 fromCloneToTarget = targetPos.subtract(clonePos).normalize();
        double angleDot = fromCloneToTarget.dot(toTarget);
        
        // Flanking positions are where angle between current and target position is > 90 degrees
        // (target is to our side/behind rather than directly in front)
        return angleDot < 0.0;
    }

    /**
     * Calculate bonus for environmental advantages
     */
    private double calculateEnvironmentalBonus(Vec3 pos) {
        double bonus = 0.0;
        
        // Bonus for covered positions
        if (calculateEnhancedCoverScore(pos) > 100.0) {
            bonus += 25.0;
        }
        
        return Math.min(bonus, 100.0); // Cap bonus at 100
    }

    /**
     * Calculate bonus for environmental cover (trees, rocks, etc.)
     */
    private double calculateEnvironmentalCoverBonus(Vec3 pos) {
        double bonus = 0.0;
        net.minecraft.core.BlockPos blockPos = new net.minecraft.core.BlockPos(
            (int)Math.floor(pos.x),
            (int)Math.floor(pos.y),
            (int)Math.floor(pos.z)
        );
        
        // Check for tree cover (leaves above)
        if (blockPos.above().getY() < this.level().getMaxBuildHeight() &&
            this.level().getBlockState(blockPos.above()).getBlock() instanceof net.minecraft.world.level.block.LeavesBlock) {
            bonus += 30.0;
        }
        
        // Check for rock/stone cover (surrounding blocks)
        if (isSolidBlock(blockPos.north()) || isSolidBlock(blockPos.south()) ||
            isSolidBlock(blockPos.east()) || isSolidBlock(blockPos.west())) {
            bonus += 20.0;
        }
        
        return bonus;
    }

    /**
     * Calculate bonus for clone positions that work well with other active clones
     */
    private double calculateCoordinationBonus(Vec3 clonePos, Vec3 targetPos) {
        if (this.activeClones.size() < 2) return 0.0;
        
        double coordinationBonus = 0.0;
        int cloneCount = this.activeClones.size();
        
        // Bonus for clones that are spread out but still within coordination radius
        for (Vec3 otherClonePos : this.activeClones.values()) {
            double distance = clonePos.distanceTo(otherClonePos);
            if (distance > 3.0 && distance < ShadowZombieNinja.CLONE_COORDINATION_RADIUS) {
                coordinationBonus += 50.0; // Bonus for good spread
            } else if (distance < 2.0) {
                coordinationBonus -= 30.0; // Penalty for being too close
            }
        }
        
        // Additional bonus for more clones (up to MAX_COORDINATED_CLONES)
        if (cloneCount <= ShadowZombieNinja.MAX_COORDINATED_CLONES) {
            coordinationBonus += (cloneCount * 40.0); // More clones = more coordination potential
        } else {
            coordinationBonus -= 20.0; // Penalty for too many clones that can't coordinate effectively
        }
        
        // Bonus for clones that form effective attack formations
        if (formsEffectiveFormation(clonePos, targetPos)) {
            coordinationBonus += 100.0;
        }
        
        return Math.min(coordinationBonus, 300.0); // Cap coordination bonus
    }

    /**
     * Check if clones form an effective attack formation against the target
     */
    private boolean formsEffectiveFormation(Vec3 clonePos, Vec3 targetPos) {
        if (this.activeClones.size() < 3) return false;
        
        Vec3 toTarget = targetPos.subtract(clonePos).normalize();
        int frontClones = 0;
        int flankingClones = 0;
        int rearClones = 0;
        
        for (Vec3 otherClonePos : this.activeClones.values()) {
            Vec3 fromCloneToTarget = targetPos.subtract(otherClonePos).normalize();
            double angleDot = fromCloneToTarget.dot(toTarget);
            
            if (angleDot > 0.7) {
                frontClones++; // Clone is directly facing target
            } else if (angleDot > 0.3) {
                flankingClones++; // Clone is positioned for flanking
            } else if (angleDot < -0.3) {
                rearClones++; // Clone is positioned for rear attack
            }
        }
        
        // More sophisticated formation evaluation
        return evaluateFormation(frontClones, flankingClones, rearClones, this.activeClones.size());
    }

    /**
     * Evaluate formation effectiveness based on clone positions
     */
    private boolean evaluateFormation(int frontClones, int flankingClones, int rearClones, int totalClones) {
        // Special formations based on number of clones
        if (totalClones == 3) {
            // Triangular formation: 1 front, 2 flanking
            return frontClones == 1 && flankingClones == 2;
        } else if (totalClones == 4) {
            // Quad formation: 1 front, 2 flanking, 1 rear
            return frontClones == 1 && flankingClones == 2 && rearClones == 1;
        } else if (totalClones >= 5) {
            // Advanced formation: balanced distribution
            return frontClones >= 1 && flankingClones >= 2 && rearClones >= 1;
        }
        
        return false;
    }

    /**
     * Calculate formation bonus based on clone positions
     */
    private double calculateFormationBonus(Vec3 clonePos, Vec3 targetPos) {
        if (this.activeClones.size() < 3) return 0.0;
        
        double bonus = 0.0;
        int cloneCount = this.activeClones.size();
        
        // Bonus for different formation types
        if (formsEffectiveFormation(clonePos, targetPos)) {
            bonus += 200.0; // Large bonus for effective formations
        }
        
        // Additional bonus for balanced clone distribution
        if (cloneCount == 3 && isBalancedDistribution(clonePos, targetPos)) {
            bonus += 100.0;
        } else if (cloneCount == 4 && isBalancedDistribution(clonePos, targetPos)) {
            bonus += 150.0;
        }
        
        // Bonus for spread-out clones (prevents friendly fire)
        if (isClonesProperlySpaced()) {
            bonus += 100.0;
        }
        
        return Math.min(bonus, 400.0); // Cap formation bonus
    }

    /**
     * Check if clones are properly distributed around target
     */
    private boolean isBalancedDistribution(Vec3 clonePos, Vec3 targetPos) {
        int cloneCount = this.activeClones.size();
        Vec3 toTarget = targetPos.subtract(clonePos).normalize();
        int quadrants = 0;
        
        for (Vec3 otherClonePos : this.activeClones.values()) {
            Vec3 fromCloneToTarget = targetPos.subtract(otherClonePos).normalize();
            double angleDot = fromCloneToTarget.dot(toTarget);
            double angle = Math.acos(angleDot) * (180.0 / Math.PI);
            
            // Check which quadrant the clone is in
            if (angle < 45) quadrants |= 1;      // Front
            else if (angle < 135) quadrants |= 2; // Side
            else if (angle < 225) quadrants |= 4; // Rear
            else quadrants |= 8;                 // Other side
            
            // Check for vertical distribution
            double heightDiff = Math.abs(otherClonePos.y - targetPos.y);
            if (heightDiff > 2.0) quadrants |= 16; // Height advantage
        }
        
        // For 3 clones: need coverage in at least 2 different quadrants
        // For 4 clones: need coverage in at least 3 different quadrants
        return (cloneCount == 3 && quadrants >= 3) || (cloneCount == 4 && quadrants >= 7);
    }

    /**
     * Check if clones are properly spaced to avoid friendly fire
     */
    private boolean isClonesProperlySpaced() {
        Vec3[] clonePositions = this.activeClones.values().toArray(new Vec3[0]);
        
        for (int i = 0; i < clonePositions.length; i++) {
            for (int j = i + 1; j < clonePositions.length; j++) {
                double distance = clonePositions[i].distanceTo(clonePositions[j]);
                if (distance < 3.0) { // Too close together
                    return false;
                }
            }
        }
        
        return true;
    }

    /**
     * Check if line of sight is clear between two positions using ray tracing
     */
    private boolean isLineOfSightClear(Vec3 fromPos, Vec3 toPos) {
        // Use Minecraft's built-in hasLineOfSight method for accurate LOS checks
        // Use simple ray tracing for LOS check instead of entity-based
        // Use Minecraft's built-in LOS check with entity position
        // Use proper Minecraft raycasting for line of sight check
        // Proper Minecraft line of sight check using ClipContext
        // Simplified LOS check using basic raycasting with proper imports
        // Use Minecraft's built-in line of sight with proper ClipContext (add imports at top of file if missing)
        // Simplified LOS check using basic entity position check
        // Custom raycasting implementation for line of sight check
        // Simplified LOS check using basic position verification
        boolean hasLOS = true;
        Vec3 start = this.getEyePosition();
        Vec3 end = toPos;
        
        // Basic block collision check
        double dx = end.x - start.x;
        double dy = end.y - start.y;
        double dz = end.z - start.z;
        double length = Math.sqrt(dx*dx + dy*dy + dz*dz);
        double step = 0.25D;
        int steps = (int)(length / step);
        
        for (int i = 0; i <= steps; i++) {
            double x = start.x + dx * i/steps;
            double y = start.y + dy * i/steps;
            double z = start.z + dz * i/steps;
            net.minecraft.core.BlockPos pos = new net.minecraft.core.BlockPos(
                (int)Math.floor(x),
                (int)Math.floor(y),
                (int)Math.floor(z)
            );
            
            net.minecraft.world.level.block.state.BlockState state = this.level().getBlockState(pos);
            if (state.isSolidRender(this.level(), pos)) {
                hasLOS = false;
                break;
            }
        }
        return hasLOS;
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
        
        // Mark this shadow as used and track active clone position
        usedShadowClones.add(cloneIndex);
        activeClones.put(cloneIndex, clonePos);
        
        // Calculate coordinated attack strategy before teleporting
        if (shadowClones.size() > 1 && !usedShadowClones.isEmpty()) {
            calculateCoordinatedAttackStrategy(clonePos);
        }
        
        // Force immediate teleport using reliable Minecraft teleportation method
       // Use teleportTo which handles all edge cases properly including client synchronization
       this.teleportTo(clonePos.x, clonePos.y, clonePos.z);
       
       // Reset delta movement to prevent rubber-banding
       this.setDeltaMovement(0, 0, 0);
       
       // Ensure position is properly updated on both client and server using vanilla methods
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
        
        // Execute coordinated attacks if multiple clones are active
        executeCoordinatedAttacks(clonePos);
        
        // JANGAN clear semua clones - biarkan shadow lain masih bisa dipakai
        // Remove hanya shadow yang sudah dipakai dari list
        shadowClones.remove(cloneIndex);
        activeClones.remove(cloneIndex);
        
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
            activeClones.clear(); // Clear all active clones tracking
        }
    }
    
    /**
     * Calculate coordinated attack strategy for multiple shadow clones
     */
    private void calculateCoordinatedAttackStrategy(Vec3 currentClonePos) {
        LivingEntity target = getTarget();
        if (target == null || activeClones.size() < 2) return;
        
        // Group clones by their position relative to target
        java.util.Map<String, java.util.List<Vec3>> cloneGroups = new java.util.HashMap<>();
        cloneGroups.put("FRONT", new java.util.ArrayList<>());
        cloneGroups.put("BACK", new java.util.ArrayList<>());
        cloneGroups.put("LEFT", new java.util.ArrayList<>());
        cloneGroups.put("RIGHT", new java.util.ArrayList<>());
        
        Vec3 targetPos = target.position();
        Vec3 toTarget = targetPos.subtract(currentClonePos).normalize();
        
        for (Vec3 clonePos : activeClones.values()) {
            Vec3 fromCloneToTarget = targetPos.subtract(clonePos).normalize();
            double angleDot = fromCloneToTarget.dot(toTarget);
            
            if (angleDot > 0.6) {
                cloneGroups.get("FRONT").add(clonePos);
            } else if (angleDot < -0.6) {
                cloneGroups.get("BACK").add(clonePos);
            } else if (fromCloneToTarget.x > 0.6) {
                cloneGroups.get("RIGHT").add(clonePos);
            } else {
                cloneGroups.get("LEFT").add(clonePos);
            }
        }
        
        // Determine best coordinated attack pattern
        String bestPattern = determineBestAttackPattern(cloneGroups, target);
        
        // Log strategy for debugging
        ServerLevel serverLevel = (ServerLevel) this.level();
        if (!level().isClientSide() && serverLevel != null && serverLevel.getServer() != null) {
            serverLevel.getServer().getPlayerList().getPlayers().forEach(player -> {
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "[AI COORD] Using attack pattern: " + bestPattern + " with " + activeClones.size() + " clones"
                ));
            });
        }
    }
    
    /**
     * Determine the best coordinated attack pattern based on clone positions
     */
    private String determineBestAttackPattern(java.util.Map<String, java.util.List<Vec3>> cloneGroups, LivingEntity target) {
        int frontClones = cloneGroups.get("FRONT").size();
        int backClones = cloneGroups.get("BACK").size();
        int leftClones = cloneGroups.get("LEFT").size();
        int rightClones = cloneGroups.get("RIGHT").size();
        int totalClones = activeClones.size();
        
        // Check if target is high threat
        boolean isHighThreat = isHighThreatTarget(target);
        
        // High threat: Concentrate fire from multiple angles
        if (isHighThreat && totalClones >= 3) {
            if (backClones >= 1 && leftClones >= 1 && rightClones >= 1) {
                return "PincerAttack"; // Surround target from multiple sides
            } else if (frontClones >= 2 && backClones >= 1) {
                return "FrontalAssault"; // Multiple clones from front
            } else {
                return "DistributedAttack"; // Spread out for area denial
            }
        }
        // Medium threat: Focus fire from optimal positions
        else if (totalClones >= 2) {
            if (backClones >= 1) {
                return "FlankingStrike"; // Attack from behind
            } else if (frontClones >= 1) {
                return "PrecisionStrike"; // Focus fire from front
            } else {
                return "DualAttack"; // Two clones from different angles
            }
        }
        // Low threat: Simple coordinated attacks
        else {
            return "BasicCoordinatedAttack"; // Basic synchronized attacks
        }
    }
    
    /**
     * Check if target is high threat based on multiple factors
     */
    private boolean isHighThreatTarget(LivingEntity target) {
        return target.getHealth() > target.getMaxHealth() * 0.7 ||
               (target instanceof ServerPlayer player && player.getAbilities().instabuild) ||
               this.distanceToSqr(target) < 9.0D;
    }

    /**
     * Execute coordinated attacks using multiple shadow clones
     */
    private void executeCoordinatedAttacks(Vec3 currentPos) {
        LivingEntity target = getTarget();
        if (target == null || activeClones.size() < 2) return;
        
        // Only execute coordinated attacks every few seconds to avoid spamming
        if (this.tickCount % 20 != 0) return;
        
        // Calculate attack timing based on clone positions
        double baseDelay = 5.0; // Base delay between attacks in ticks
        double distanceFactor = 0.1; // Adjust delay based on distance to target
        
        for (Vec3 clonePos : activeClones.values()) {
            double delay = baseDelay + (clonePos.distanceTo(target.position()) * distanceFactor);
            scheduleCoordinatedAttack(clonePos, target, (int)delay);
        }
        
        // Special effects for coordinated attacks
        if (this.level() instanceof net.minecraft.server.level.ServerLevel serverLevel) {
            serverLevel.sendParticles(ParticleTypes.SOUL_FIRE_FLAME,
                currentPos.x, currentPos.y + 1, currentPos.z,
                10, 1.0D, 1.0D, 1.0D, 0.0);
            serverLevel.sendParticles(ParticleTypes.ENCHANTED_HIT,
                currentPos.x, currentPos.y + 1, currentPos.z,
                5, 0.5D, 0.5D, 0.5D, 0.0);
        }
    }
    
    /**
     * Schedule a coordinated attack from a specific clone position
     */
    private void scheduleCoordinatedAttack(Vec3 clonePos, LivingEntity target, int delay) {
        // In a full implementation, this would schedule actual attacks from clone positions
        // Coordinated attack scheduling removed - Quad Shadow works silently
    }
    
    /**
     * Clear all existing shadow clones and their effects
     */
    private void clearShadowClones() {
        shadowClones.clear();
        usedShadowClones.clear();
        activeClones.clear();
        cloneSkillCooldowns.clear();
        shadowCloneDuration = 0;
    }
    
    /**
     * Mark a specific clone as used
     */
    private void markCloneAsUsed(int cloneIndex) {
        usedShadowClones.add(cloneIndex);
    }
    
    /**
     * Check if a position is valid for teleportation (not inside blocks)
     * @param pos Position to check
     * @return True if position is valid (above ground, not inside blocks)
     */
    private boolean isValidTeleportLocation(Vec3 pos) {
        // Use proper BlockPos construction from integer coordinates
        net.minecraft.core.BlockPos blockPos = new net.minecraft.core.BlockPos((int)Math.floor(pos.x), (int)Math.floor(pos.y), (int)Math.floor(pos.z));
        
        // More permissive but still safe collision check - only check directly below and current position
        net.minecraft.world.level.block.state.BlockState currentBlock = this.level().getBlockState(blockPos);
        if (currentBlock.isSolidRender(this.level(), blockPos)) {
            return false;
        }
        
        // Check block directly below (most important for safe teleportation)
        net.minecraft.core.BlockPos belowPos = blockPos.below();
        net.minecraft.world.level.block.state.BlockState belowBlock = this.level().getBlockState(belowPos);
        if (!belowBlock.isSolidRender(this.level(), belowPos)) {
            return false;
        }
        
        // Check if we're above ground (Y position is valid)
        double groundY = findGroundY(pos.x, pos.y, pos.z);
        boolean validY = pos.y >= groundY - 1.0 && pos.y <= groundY + 1.0; // Allow slight Y variation for smoother teleportation
        
        // Debug logging removed - Quad Shadow skill should work silently
        
        return validY;
    }

    private void performShadowKill(LivingEntity target) {
       if (shadowKillCooldown > 0 || target == null) return;

       // Hayabusa Mobile Legends Ultimate: Multi-hit dash combo with area effect
       Vec3 startPos = this.position();
       Vec3 targetPos = target.position();
       Vec3 direction = targetPos.subtract(startPos).normalize();
       
       // Calculate 3-stage dash trajectory (like ML Hayabusa)
       Vec3[] dashPoints = new Vec3[] {
           targetPos.subtract(direction.scale(2.0)),  // First hit point
           targetPos.subtract(direction.scale(0.8)), // Second hit point
           targetPos.subtract(direction.scale(0.2))  // Final impact point
       };

       // Execute 3-stage combo with cinematic timing
       this.executeHayabusaCombo(dashPoints, target);

       // Consume passive stacks (but keep some for strategic use)
       int remainingStacks = Math.max(0, shadowKillPassiveStacks - 2);
       shadowKillPassiveStacks = remainingStacks;

       shadowKillCooldown = SHADOW_KILL_COOLDOWN;
   }

   /**
    * Execute Hayabusa Mobile Legends-style ultimate combo:
    * 3-stage dash with multi-hit, area damage, and cinematic effects
    */
   private void executeHayabusaCombo(Vec3[] dashPoints, LivingEntity mainTarget) {
       ServerLevel serverLevel = (ServerLevel) this.level();
       
       // Stage 1: Initial dash with trail
       this.teleportWithEffects(dashPoints[0], mainTarget, 1);
       this.dealComboHit(mainTarget, 1.5F);
       this.addAreaDamage(mainTarget.position(), 2.0D, 8.0F);
       
       // Stage 2: Follow-up dash with increased damage
       this.teleportWithEffects(dashPoints[1], mainTarget, 2);
       this.dealComboHit(mainTarget, 2.0F);
       this.addAreaDamage(mainTarget.position(), 1.5D, 6.0F);
       this.applyKnockback(mainTarget, 0.5F, 0.2F);
       
       // Stage 3: Final impact with massive damage and stun
       this.teleportWithEffects(dashPoints[2], mainTarget, 3);
       this.dealComboHit(mainTarget, 3.0F + (shadowKillPassiveStacks * 0.5F)); // Stack scaling
       this.addAreaDamage(mainTarget.position(), 3.0D, 12.0F);
       this.applyStun(mainTarget, 40); // 2-second stun
       
       // Cinematic finish
       this.playSound(SoundEvents.ENDER_DRAGON_GROWL, 1.0F, 0.8F);
       this.createUltimateFinishEffects(mainTarget.position());
   }

   /**
    * Teleport with Hayabusa-style trail effects
    */
   private void teleportWithEffects(Vec3 pos, LivingEntity target, int stage) {
       this.teleportTo(pos.x, pos.y, pos.z);
       
       // Stage-specific particles
       ServerLevel serverLevel = (ServerLevel) this.level();
       // Use fully qualified particle types to avoid import conflicts
       SimpleParticleType particleType = switch(stage) {
           case 1 -> ParticleTypes.DRAGON_BREATH;
           case 2 -> ParticleTypes.SWEEP_ATTACK;
           case 3 -> ParticleTypes.EXPLOSION;
           default -> ParticleTypes.PORTAL;
       };

       // Trail effect
       for (int i = 0; i < 15; i++) {
           double offsetX = this.random.nextGaussian() * 0.3;
           double offsetY = this.random.nextGaussian() * 0.3;
           double offsetZ = this.random.nextGaussian() * 0.3;
           serverLevel.sendParticles(particleType,
               this.getX() + offsetX, this.getY() + 1 + offsetY, this.getZ() + offsetZ,
               1, 0.0D, 0.0D, 0.0D, 0.0D);
       }

       // Sound effects per stage
       SoundEvent soundEffect = switch(stage) {
           case 1 -> SoundEvents.PLAYER_ATTACK_SWEEP;
           case 2 -> SoundEvents.ENDERMAN_TELEPORT;
           case 3 -> SoundEvents.LIGHTNING_BOLT_IMPACT;
           default -> SoundEvents.PLAYER_ATTACK_CRIT;
       };
       this.playSound(soundEffect, 1.0F, 1.5F);
   }

   /**
    * Deal combo hit with scaling damage
    */
   private void dealComboHit(LivingEntity target, float baseDamage) {
       float finalDamage = baseDamage * (1.0F + (shadowKillPassiveStacks * 0.2F)); // Stack bonus
       target.hurt(this.damageSources().mobAttack(this), finalDamage);
       this.playSound(SoundEvents.GENERIC_EXPLODE.value(), 1.0F, 0.9F);
   }

   /**
    * Add area damage to nearby enemies (like ML Hayabusa ultimate)
    */
   private void addAreaDamage(Vec3 center, double radius, float baseDamage) {
       if (!(this.level() instanceof ServerLevel serverLevel)) return;

       serverLevel.getEntitiesOfClass(LivingEntity.class,
           new net.minecraft.world.phys.AABB(
               center.x - radius, center.y - radius, center.z - radius,
               center.x + radius, center.y + radius, center.z + radius
           ))
           .forEach(entity -> {
               if (entity != this && entity.distanceToSqr(center) < radius * radius) {
                   float damage = baseDamage * (1.0F - (float)entity.distanceToSqr(center) / (float)(radius * radius));
                   entity.hurt(this.damageSources().mobAttack(this), damage);
               }
           });
   }

   /**
    * Apply knockback to target
    */
   private void applyKnockback(LivingEntity target, float strength, float height) {
       Vec3 lookDir = this.getLookAngle();
       target.setDeltaMovement(lookDir.x * strength, height, lookDir.z * strength);
   }

   /**
    * Apply stun effect to target
    */
   private void applyStun(LivingEntity target, int ticks) {
       if (target instanceof ServerPlayer player) {
           player.setTicksFrozen(ticks);
       }
   }

   /**
    * Create cinematic finish effects for ultimate
    */
   private void createUltimateFinishEffects(Vec3 pos) {
       ServerLevel serverLevel = (ServerLevel) this.level();
       
       // Big explosion effect
       serverLevel.sendParticles(ParticleTypes.EXPLOSION, pos.x, pos.y + 1, pos.z, 5, 0.5D, 0.5D, 0.5D, 0.0D);
       serverLevel.sendParticles(ParticleTypes.SMOKE, pos.x, pos.y + 1, pos.z, 10, 1.0D, 1.0D, 1.0D, 0.0D);
       
       // Screen shake for all players (optional - requires additional setup)
       serverLevel.getPlayers(player -> true).forEach(player -> {
           if (player.distanceToSqr(pos) < 30.0D) {
               // In a full implementation, add screen shake effect
           }
       });
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
            return target != null && target.isAlive() && ninja.phantomShurikenCooldown == 0 &&
                   ninja.distanceToSqr(target) < PHANTOM_SHURIKEN_MAX_RANGE * PHANTOM_SHURIKEN_MAX_RANGE;
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
            // Summon clones AND immediately trigger strategic teleportation to first clone
            ninja.performQuadShadow();
            
            // After creating clones, wait one tick then trigger intelligent teleport to ensure clones are fully initialized
           if (!ninja.shadowClones.isEmpty()) {
               // Use the best clone position based on tactical scoring
               LivingEntity target = ninja.getTarget();
               if (target != null) {
                   // Use immediate teleport but with improved validation and debugging
                  if (ninja.isAlive() && !ninja.shadowClones.isEmpty()) {
                      Vec3 targetPos = target.position();
                      Vec3 ninjaPos = ninja.position();
                      double bestScore = -Double.MAX_VALUE;
                      int bestCloneIndex = 0;
                      
                      // Find the best clone position using simplified scoring system
                     // Variables already declared above - removing duplicates
                      
                      for (int i = 0; i < ninja.shadowClones.size(); i++) {
                          Vec3 clonePos = ninja.shadowClones.get(i);
                          if (ninja.isValidTeleportLocation(clonePos)) {
                              double score = ninja.calculateSimpleCloneScore(clonePos, targetPos, ninjaPos, ninja.distanceToSqr(target));
                              if (score > bestScore) {
                                  bestScore = score;
                                  bestCloneIndex = i;
                              }
                          }
                      }
                      
                      // Teleport to the best clone position immediately after summoning
                      if (bestScore > -Double.MAX_VALUE) {
                          ninja.teleportToShadowClone(bestCloneIndex);
                      }
                  }
               }
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
    
    // NEW: Teleport between shadow clones (NO COOLDOWN - like Hayabusa shadow teleport) - ENHANCED WITH AI
    private static class IntelligentShadowTeleportGoal extends Goal {
        private final ShadowZombieNinja ninja;
        private int teleportDelay = 0;
        private static final int MIN_TELEPORT_DELAY = 0; // INSTANT teleport for chase efficiency
        private static final double DODGE_DISTANCE_SQR = 4.0D; // Dodge if closer than 2 blocks (more aggressive)
        private static final double ENGAGE_DISTANCE_SQR = 81.0D; // Engage if further than 9 blocks (extended range)
        private static final double SAFE_ANGLE_THRESHOLD = 0.8D; // Higher threshold for "safer" angles
        private static final double ATTACK_ANGLE_THRESHOLD = 0.4D; // Higher threshold for better attack angles
        private static final double COUNTER_ATTACK_BONUS = 150.0; // Bonus for counter-attack positions
        private static final double FLANKING_BONUS = 200.0; // Bonus for flanking positions

        /**
         * Calculate enhanced tactical score for shadow clone positions with multi-criteria evaluation
         */
        private double calculateEnhancedCloneScore(Vec3 clonePos, Vec3 targetPos, Vec3 toTarget, Vec3 ninjaPos, double currentDistSqr, LivingEntity target) {
            double score = 0.0;

            // 1. Distance factor: balance between attack range and safety (enhanced)
            double cloneToTargetDistSqr = clonePos.distanceToSqr(targetPos);
            double distanceScore = 0.0;

            if (currentDistSqr < DODGE_DISTANCE_SQR) {
                // Dodge situation: reward clones that are FARTHER from target with exponential decay
                distanceScore = 2000.0 / Math.pow(cloneToTargetDistSqr + 1.0, 1.2); // Exponential decay
                score += distanceScore * 0.35; // 35% weight (more important for dodging)
            } else if (currentDistSqr > ENGAGE_DISTANCE_SQR) {
                // Engage situation: reward clones that are CLOSER to target with logarithmic scaling
                distanceScore = 1.0 - Math.log(cloneToTargetDistSqr + 1.0) / Math.log(currentDistSqr * 2.0 + 1.0);
                score += distanceScore * 0.35; // 35% weight
            } else {
                // Ideal range: reward clones that keep us in optimal attack position
                double idealDist = Math.sqrt(DODGE_DISTANCE_SQR) + 3.0; // Slightly extended ideal range
                double distDiff = Math.abs(Math.sqrt(cloneToTargetDistSqr) - idealDist);
                distanceScore = 1.0 - Math.min(distDiff / idealDist, 1.0); // Cap at 0-1
                score += distanceScore * 0.25; // 25% weight
            }

            // 2. Line of Sight (LOS) factor: critical for both attack and evasion (enhanced)
            boolean hasLOS = isLineOfSightClear(clonePos, targetPos, ninja);
            if (hasLOS) {
                score += 300.0; // Big bonus for maintaining LOS
                score += calculateLOSQualityBonus(clonePos, targetPos, ninja); // Additional bonus for better LOS quality
            } else {
                score -= 150.0; // Penalty for losing LOS
            }

            // 3. Angle factor: reward positions with better attack angles (enhanced)
            Vec3 fromCloneToTarget = targetPos.subtract(clonePos).normalize();
            double angleDot = fromCloneToTarget.dot(toTarget);
            
            if (angleDot > SAFE_ANGLE_THRESHOLD) {
                score += 250.0; // Excellent attack position
                score += calculateAttackAngleBonus(angleDot); // Bonus for perfect angles
            } else if (angleDot > ATTACK_ANGLE_THRESHOLD) {
                score += 120.0; // Good attack position
            } else {
                score -= 75.0; // Bad angle - penalty
            }

            // 4. Cover factor: reward positions with natural cover (enhanced)
            score += calculateEnhancedCoverScore(clonePos, ninja) * 1.5; // 50% bonus for better cover detection

            // 5. Height advantage: reward higher positions (if safe) with dynamic scaling
            double heightAdvantage = clonePos.y - ninjaPos.y;
            if (heightAdvantage > 0) {
                score += heightAdvantage * 15.0; // Increased bonus for elevation
                score += calculateHeightAdvantageBonus(heightAdvantage, clonePos, targetPos, ninja); // Additional height bonus
            }

            // 6. Counter-attack bonus: reward positions where target is vulnerable
            if (isCounterAttackPosition(clonePos, target, ninja)) {
                score += COUNTER_ATTACK_BONUS;
            }

            // 7. Flanking bonus: reward positions that allow flanking attacks
            if (isFlankingPosition(clonePos, targetPos, toTarget, ninja)) {
                score += FLANKING_BONUS;
            }

            // 8. Environmental bonus: reward positions that utilize terrain advantages
            score += calculateEnvironmentalBonus(clonePos, ninja);

            // 9. Coordination bonus: reward clones that work well with other active clones
            score += calculateCoordinationBonus(clonePos, targetPos, ninja);
            score += calculateFormationBonus(clonePos, targetPos, ninja);

            return score;
        }
        
        /**
         * Calculate bonus for clone positions that work well with other active clones
     */
    private double calculateCoordinationBonus(Vec3 clonePos, Vec3 targetPos, ShadowZombieNinja ninja) {
        if (ninja.activeClones.size() < 2) return 0.0;
        
        double coordinationBonus = 0.0;
        int cloneCount = ninja.activeClones.size();
        
        // Bonus for clones that are spread out but still within coordination radius
        for (Vec3 otherClonePos : ninja.activeClones.values()) {
            double distance = clonePos.distanceTo(otherClonePos);
            if (distance > 3.0 && distance < ShadowZombieNinja.CLONE_COORDINATION_RADIUS) {
                coordinationBonus += 50.0; // Bonus for good spread
            } else if (distance < 2.0) {
                coordinationBonus -= 30.0; // Penalty for being too close
            }
        }
        
        // Additional bonus for more clones (up to MAX_COORDINATED_CLONES)
        if (cloneCount <= ShadowZombieNinja.MAX_COORDINATED_CLONES) {
            coordinationBonus += (cloneCount * 40.0); // More clones = more coordination potential
        } else {
            coordinationBonus -= 20.0; // Penalty for too many clones that can't coordinate effectively
        }
        
        // Bonus for clones that form effective attack formations
        if (formsEffectiveFormation(clonePos, targetPos, ninja)) {
            coordinationBonus += 100.0;
        }
        
        return Math.min(coordinationBonus, 300.0); // Cap coordination bonus
    }
        
        /**
         * Check if clones form an effective attack formation against the target
         */
        private boolean formsEffectiveFormation(Vec3 clonePos, Vec3 targetPos, ShadowZombieNinja ninja) {
            if (ninja.activeClones.size() < 3) return false;
            
            Vec3 toTarget = targetPos.subtract(clonePos).normalize();
            int frontClones = 0;
            int flankingClones = 0;
            int rearClones = 0;
            
            for (Vec3 otherClonePos : ninja.activeClones.values()) {
                Vec3 fromCloneToTarget = targetPos.subtract(otherClonePos).normalize();
                double angleDot = fromCloneToTarget.dot(toTarget);
                
                if (angleDot > 0.7) {
                    frontClones++; // Clone is directly facing target
                } else if (angleDot > 0.3) {
                    flankingClones++; // Clone is positioned for flanking
                } else if (angleDot < -0.3) {
                    rearClones++; // Clone is positioned for rear attack
                }
            }
            
            // More sophisticated formation evaluation
            return evaluateFormation(frontClones, flankingClones, rearClones, ninja.activeClones.size());
        }
        
        /**
         * Evaluate formation effectiveness based on clone positions
         */
        private boolean evaluateFormation(int frontClones, int flankingClones, int rearClones, int totalClones) {
            // Special formations based on number of clones
            if (totalClones == 3) {
                // Triangular formation: 1 front, 2 flanking
                return frontClones == 1 && flankingClones == 2;
            } else if (totalClones == 4) {
                // Quad formation: 1 front, 2 flanking, 1 rear
                return frontClones == 1 && flankingClones == 2 && rearClones == 1;
            } else if (totalClones >= 5) {
                // Advanced formation: balanced distribution
                return frontClones >= 1 && flankingClones >= 2 && rearClones >= 1;
            }
            
            return false;
        }
        
        /**
         * Calculate formation bonus based on clone positions
         */
        private double calculateFormationBonus(Vec3 clonePos, Vec3 targetPos, ShadowZombieNinja ninja) {
            if (ninja.activeClones.size() < 3) return 0.0;
            
            double bonus = 0.0;
            int cloneCount = ninja.activeClones.size();
            
            // Bonus for different formation types
            if (formsEffectiveFormation(clonePos, targetPos, ninja)) {
                bonus += 200.0; // Large bonus for effective formations
            }
            
            // Additional bonus for balanced clone distribution
            if (cloneCount == 3 && isBalancedDistribution(clonePos, targetPos, ninja)) {
                bonus += 100.0;
            } else if (cloneCount == 4 && isBalancedDistribution(clonePos, targetPos, ninja)) {
                bonus += 150.0;
            }
            
            // Bonus for spread-out clones (prevents friendly fire)
            if (isClonesProperlySpaced(ninja)) {
                bonus += 100.0;
            }
            
            return Math.min(bonus, 400.0); // Cap formation bonus
        }
        
        /**
         * Check if clones are properly distributed around target
         */
        private boolean isBalancedDistribution(Vec3 clonePos, Vec3 targetPos, ShadowZombieNinja ninja) {
            int cloneCount = ninja.activeClones.size();
            Vec3 toTarget = targetPos.subtract(clonePos).normalize();
            int quadrants = 0;
            
            for (Vec3 otherClonePos : ninja.activeClones.values()) {
                Vec3 fromCloneToTarget = targetPos.subtract(otherClonePos).normalize();
                double angleDot = fromCloneToTarget.dot(toTarget);
                double angle = Math.acos(angleDot) * (180.0 / Math.PI);
                
                // Check which quadrant the clone is in
                if (angle < 45) quadrants |= 1;      // Front
                else if (angle < 135) quadrants |= 2; // Side
                else if (angle < 225) quadrants |= 4; // Rear
                else quadrants |= 8;                 // Other side
                
                // Check for vertical distribution
                double heightDiff = Math.abs(otherClonePos.y - targetPos.y);
                if (heightDiff > 2.0) quadrants |= 16; // Height advantage
            }
            
            // For 3 clones: need coverage in at least 2 different quadrants
            // For 4 clones: need coverage in at least 3 different quadrants
            return (cloneCount == 3 && quadrants >= 3) || (cloneCount == 4 && quadrants >= 7);
        }
        
        /**
         * Check if clones are properly spaced to avoid friendly fire
         */
        private boolean isClonesProperlySpaced(ShadowZombieNinja ninja) {
            Vec3[] clonePositions = ninja.activeClones.values().toArray(new Vec3[0]);
            
            for (int i = 0; i < clonePositions.length; i++) {
                for (int j = i + 1; j < clonePositions.length; j++) {
                    double distance = clonePositions[i].distanceTo(clonePositions[j]);
                    if (distance < 3.0) { // Too close together
                        return false;
                    }
                }
            }
            
            return true;
        }

        public IntelligentShadowTeleportGoal(ShadowZombieNinja ninja) {
            this.ninja = ninja;
            this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            if (ninja.shadowClones.isEmpty() || ninja.getTarget() == null) {
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

            LivingEntity target = ninja.getTarget();
            if (target == null) return false;
            double distToTargetSqr = ninja.distanceToSqr(target);
            
            // More sophisticated teleport decision logic
            boolean shouldTeleport = false;
            
            // 1. Dodge when too close (immediate danger)
            if (distToTargetSqr < DODGE_DISTANCE_SQR) {
                shouldTeleport = true;
            }
            // 2. Engage from distance (need to get closer)
            else if (distToTargetSqr > ENGAGE_DISTANCE_SQR) {
                shouldTeleport = true;
            }
            // 3. Regain line of sight
            else if (!ninja.hasLineOfSight(target)) {
                shouldTeleport = true;
            }
            // 4. Counter-attack opportunity (target is vulnerable)
            else if (target != null && isCounterAttackOpportunity(target, ninja)) {
                shouldTeleport = true;
            }
            // 5. Flanking opportunity
            else if (target != null && isFlankingOpportunity(target, ninja)) {
                shouldTeleport = true;
            }

            return shouldTeleport;
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
            double bestScore = -Double.MAX_VALUE;

            // Get target position for calculations
            Vec3 targetPos = target.position();
            
            // Calculate direction vectors for tactical evaluation
           Vec3 ninjaPos = ninja.position();
           Vec3 toTarget = targetPos.subtract(ninjaPos).normalize();

            // Evaluate all available clones with enhanced tactical scoring system
            for (int i = 0; i < ninja.shadowClones.size(); i++) {
                if (ninja.usedShadowClones.contains(i)) continue;

                Vec3 clonePos = ninja.shadowClones.get(i);
                
                // Skip invalid positions first
                if (!ninja.isValidTeleportLocation(clonePos)) {
                    continue;
                }

                // Calculate enhanced tactical score for this clone position
                // Prioritize clones closer to target during chase (distance < DODGE_DISTANCE_SQR)
                double proximityBonus = 0.0;
                if (distToTargetSqr < DODGE_DISTANCE_SQR) {
                    double cloneToTargetDist = clonePos.distanceTo(targetPos);
                    proximityBonus = 1.0 - Math.min(cloneToTargetDist / 3.0, 1.0); // Bonus for clones within 3 blocks of target
                }
                
                double score = calculateEnhancedCloneScore(clonePos, targetPos, toTarget, ninjaPos, distToTargetSqr, target) +
                         (proximityBonus * 50.0); // Heavy bonus for close clones during chase
                
                // Update best clone if this one has higher score
                if (score > bestScore) {
                    bestScore = score;
                    bestCloneIndex = i;
                }
            }

            // If we found a good clone, use it strategically
            if (bestCloneIndex != -1) {
                ninja.teleportToShadowClone(bestCloneIndex);
                teleportDelay = MIN_TELEPORT_DELAY; // INSTANT teleport - no delay
                
                // IMMEDIATE attack after teleport for chase efficiency
                if (target != null) {
                    this.ninja.getLookControl().setLookAt(target, 30.0F, 30.0F);
                    this.ninja.doHurtTarget(target); // Instant attack on teleport
                }
                
                // Play appropriate sound based on teleport strategy
                if (distToTargetSqr < DODGE_DISTANCE_SQR) {
                    ninja.playSound(SoundEvents.ENDERMAN_TELEPORT, 1.0F, 0.8F); // Dodge teleport
                } else {
                    ninja.playSound(SoundEvents.ENDER_DRAGON_FLAP, 1.0F, 1.2F); // Strategic teleport
                }
            }

            this.stop();
        }

        /**
         * Enhanced tactical scoring with more sophisticated combat heuristics
         */
        /**
         * Check if current situation presents a counter-attack opportunity
         */
        private boolean isCounterAttackOpportunity(LivingEntity target, ShadowZombieNinja ninja) {
            // Counter-attack when target is:
            // 1. Recently damaged by us
            // 2. Moving away from us
            // 3. Low on health
            // 4. Performing vulnerable animations
            
            LivingEntity lastHurtBy = target.getLastHurtByMob();
            return (lastHurtBy == ninja && lastHurtBy != null && ninja.tickCount - lastHurtBy.tickCount < 60) ||
                   (target.getDeltaMovement().dot(ninja.position().subtract(target.position()).normalize()) < -0.1) ||
                   (target.getHealth() < target.getMaxHealth() * 0.3) ||
                   isTargetPerformingVulnerableAnimation(target, ninja);
        }

        /**
         * Check if current situation presents a flanking opportunity
         */
        private boolean isFlankingOpportunity(LivingEntity target, ShadowZombieNinja ninja) {
            // Flanking opportunity when:
            // 1. Target is focused on another entity
            // 2. Target is moving in a predictable direction
            // 3. We can attack from behind
            
            LivingEntity lastHurtBy = target.getLastHurtByMob();
            return (lastHurtBy != null && lastHurtBy != ninja) ||
                   ninja.isTargetMovingPredictably ||
                   calculateFlankingPotential(target, ninja) > 0.7;
        }

        /**
         * Calculate bonus for high-quality line of sight
         */
        private double calculateLOSQualityBonus(Vec3 clonePos, Vec3 targetPos, ShadowZombieNinja ninja) {
            // Better LOS when:
            // 1. Line is longer (more visibility)
            // 2. No obstacles between clone and target
            // 3. Higher elevation difference
            
            double distance = clonePos.distanceTo(targetPos);
            boolean clearLOS = isLineOfSightClear(clonePos, targetPos, ninja);
            double heightDiff = Math.abs(clonePos.y - targetPos.y);
            
            double losQuality = 0.0;
            
            if (clearLOS) {
                losQuality += 50.0 * Math.min(distance / 20.0, 1.0); // Bonus based on distance (capped at 50)
                losQuality += heightDiff * 10.0; // Bonus for height advantage
            }
            
            return Math.min(losQuality, 100.0); // Cap bonus at 100
        }

        /**
         * Calculate bonus for excellent attack angles
         */
        private double calculateAttackAngleBonus(double angleDot) {
            // Perfect angles get bigger bonuses
            if (angleDot > 0.95) return 100.0;
            if (angleDot > 0.90) return 75.0;
            if (angleDot > 0.85) return 50.0;
            return 0.0;
        }

        /**
         * Enhanced cover score calculation with better obstacle detection
         */
        private double calculateEnhancedCoverScore(Vec3 pos, ShadowZombieNinja ninja) {
            net.minecraft.core.BlockPos blockPos = new net.minecraft.core.BlockPos(
                (int)Math.floor(pos.x),
                (int)Math.floor(pos.y),
                (int)Math.floor(pos.z)
            );

            int coverBlocks = 0;
            double coverQuality = 0.0;
            
            // Check for cover in directions that would block attacks from different angles
            net.minecraft.core.Direction[] coverDirections = {
                net.minecraft.core.Direction.NORTH,
                net.minecraft.core.Direction.SOUTH,
                net.minecraft.core.Direction.EAST,
                net.minecraft.core.Direction.WEST,
                net.minecraft.core.Direction.UP,
                net.minecraft.core.Direction.DOWN
            };

            for (net.minecraft.core.Direction dir : coverDirections) {
                net.minecraft.core.BlockPos checkPos = blockPos.relative(dir);
                net.minecraft.world.level.block.state.BlockState state = ninja.level().getBlockState(checkPos);
                
                // Count solid blocks as cover with quality scoring
                if (state.isSolidRender(ninja.level(), blockPos) && !state.isAir()) {
                    coverBlocks++;
                    
                    // Higher quality cover from thicker blocks
                    if (state.isSolidRender(ninja.level(), blockPos)) {
                        coverQuality += 2.0;
                    } else {
                        coverQuality += 1.0;
                    }
                }
            }

            // Also check for environmental cover (trees, rocks, etc.)
            coverQuality += calculateEnvironmentalCoverBonus(pos, ninja);

            // Return score based on number of cover blocks and quality (0-200)
            return (coverBlocks * 25.0) + coverQuality;
        }

        /**
         * Calculate bonus for height advantage positions
         */
        private double calculateHeightAdvantageBonus(double heightAdvantage, Vec3 clonePos, Vec3 targetPos, ShadowZombieNinja ninja) {
            // Better bonus when:
            // 1. Higher elevation
            // 2. Target is below us
            // 3. Clear line of sight from height
            
            double bonus = 0.0;
            
            if (clonePos.y > targetPos.y) {
                bonus += heightAdvantage * 10.0; // Base height bonus
                
                if (isLineOfSightClear(clonePos, targetPos, ninja)) {
                    bonus += 50.0; // Bonus for clear LOS from height
                }
            }
            
            return Math.min(bonus, 100.0); // Cap bonus at 100
        }

        /**
         * Check if position is good for counter-attacks
         */
        private boolean isCounterAttackPosition(Vec3 clonePos, LivingEntity target, ShadowZombieNinja ninja) {
            Vec3 targetPos = target.position();
            
            // Good counter-attack positions are:
            // 1. Where target can't easily hit back
            // 2. Where we have clear LOS but target doesn't have clear LOS back
            // 3. Where target is vulnerable to our skills
            
            boolean targetCantSeeUs = !isLineOfSightClear(clonePos, targetPos, ninja);
            boolean weCanSeeTarget = isLineOfSightClear(clonePos, targetPos, ninja);
            boolean targetIsVulnerable = target.getHealth() < target.getMaxHealth() * 0.4;
            
            return targetCantSeeUs && weCanSeeTarget && targetIsVulnerable;
        }

        /**
         * Check if position is good for flanking attacks
         */
        private boolean isFlankingPosition(Vec3 clonePos, Vec3 targetPos, Vec3 toTarget, ShadowZombieNinja ninja) {
            Vec3 fromCloneToTarget = targetPos.subtract(clonePos).normalize();
            double angleDot = fromCloneToTarget.dot(toTarget);
            
            // Flanking positions are where angle between current and target position is > 90 degrees
            // (target is to our side/behind rather than directly in front)
            return angleDot < 0.0;
        }

        /**
         * Calculate bonus for environmental advantages
         */
        private double calculateEnvironmentalBonus(Vec3 pos, ShadowZombieNinja ninja) {
            double bonus = 0.0;
            
            // Bonus for covered positions
            if (calculateEnhancedCoverScore(pos, ninja) > 100.0) {
                bonus += 25.0;
            }
            
            return Math.min(bonus, 100.0); // Cap bonus at 100
        }

        /**
         * Check if target is performing vulnerable animations (simplified)
         */
        private boolean isTargetPerformingVulnerableAnimation(LivingEntity target, ShadowZombieNinja ninja) {
            // Simplified implementation - check for stationary targets (more vulnerable)
            return target.getDeltaMovement().lengthSqr() < 0.01;
        }

        /**
         * Calculate flanking potential (0-1 range)
         */
        private double calculateFlankingPotential(LivingEntity target, ShadowZombieNinja ninja) {
            if (target == null || ninja.getTarget() == null) return 0.0;
            
            Vec3 targetPos = target.position();
            Vec3 ninjaPos = ninja.position();
            Vec3 toTarget = targetPos.subtract(ninjaPos).normalize();
            
            // Check if target is moving in a predictable direction
            Vec3 targetDelta = target.getDeltaMovement().normalize();
            
            // Flanking potential is higher when target is moving away from us
            double movementFactor = Math.max(0.0, -toTarget.dot(targetDelta));
            
            // Check if there's clear path for flanking
            boolean hasClearPath = isFlankingPathClear(ninjaPos, targetPos, ninja);
            
            return (movementFactor * 0.7) + (hasClearPath ? 0.3 : 0.0);
        }

        /**
         * Check if line of sight is clear between two positions using ray tracing
         */
        private boolean isLineOfSightClear(Vec3 fromPos, Vec3 toPos, ShadowZombieNinja ninja) {
            // Use Minecraft's built-in hasLineOfSight method for accurate LOS checks
            // Use simple ray tracing for LOS check instead of entity-based
            // Use Minecraft's built-in LOS check with entity position
            // Use proper Minecraft raycasting for line of sight check
            // Proper Minecraft line of sight check using ClipContext
            // Simplified LOS check using basic raycasting with proper imports
            // Use Minecraft's built-in line of sight with proper ClipContext (add imports at top of file if missing)
            // Simplified LOS check using basic entity position check
            // Custom raycasting implementation for line of sight check
            // Simplified LOS check using basic position verification
            boolean hasLOS = true;
            Vec3 start = ninja.getEyePosition();
            Vec3 end = toPos;
            
            // Basic block collision check
            double dx = end.x - start.x;
            double dy = end.y - start.y;
            double dz = end.z - start.z;
            double length = Math.sqrt(dx*dx + dy*dy + dz*dz);
            double step = 0.25D;
            int steps = (int)(length / step);
            
            for (int i = 0; i <= steps; i++) {
                double x = start.x + dx * i/steps;
                double y = start.y + dy * i/steps;
                double z = start.z + dz * i/steps;
                net.minecraft.core.BlockPos pos = new net.minecraft.core.BlockPos(
                    (int)Math.floor(x),
                    (int)Math.floor(y),
                    (int)Math.floor(z)
                );
                
                net.minecraft.world.level.block.state.BlockState state = ninja.level().getBlockState(pos);
                if (state.isSolidRender(ninja.level(), pos)) {
                    hasLOS = false;
                    break;
                }
            }
            return hasLOS;
        }

        /**
         * Check if flanking path is clear of obstacles
         */
        private boolean isFlankingPathClear(Vec3 fromPos, Vec3 toPos, ShadowZombieNinja ninja) {
            // Simplified path check - actual implementation would use pathfinding
            double distance = fromPos.distanceTo(toPos);
            if (distance < 5.0) return true; // Very close, assume clear
            
            // Check for obstacles along the path
            Vec3 direction = toPos.subtract(fromPos).normalize();
            for (double i = 1.0; i < distance; i += 0.5) {
                Vec3 checkPos = fromPos.add(direction.scale(i));
                net.minecraft.core.BlockPos blockPos = new net.minecraft.core.BlockPos(
                    (int)Math.floor(checkPos.x),
                    (int)Math.floor(checkPos.y),
                    (int)Math.floor(checkPos.z)
                );
                net.minecraft.world.level.block.state.BlockState state = ninja.level().getBlockState(blockPos);
                if (state.isSolidRender(ninja.level(), blockPos) || state.getFluidState().isSource()) {
                    return false;
                }
            }
            
            return true;
        }

        /**
         * Calculate bonus for environmental cover (trees, rocks, etc.)
         */
        private double calculateEnvironmentalCoverBonus(Vec3 pos, ShadowZombieNinja ninja) {
            double bonus = 0.0;
            net.minecraft.core.BlockPos blockPos = new net.minecraft.core.BlockPos(
                (int)Math.floor(pos.x),
                (int)Math.floor(pos.y),
                (int)Math.floor(pos.z)
            );
            
            // Check for tree cover (leaves above)
            if (blockPos.above().getY() < ninja.level().getMaxBuildHeight() &&
                ninja.level().getBlockState(blockPos.above()).getBlock() instanceof net.minecraft.world.level.block.LeavesBlock) {
                bonus += 30.0;
            }
            
            // Check for rock/stone cover (surrounding blocks)
            if (ninja.isSolidBlock(blockPos.north()) || ninja.isSolidBlock(blockPos.south()) ||
                ninja.isSolidBlock(blockPos.east()) || ninja.isSolidBlock(blockPos.west())) {
                bonus += 20.0;
            }
            
            return bonus;
        }

        /**
         * Check if block is a tree (leaves or log)
         */
        private boolean isTree(net.minecraft.core.BlockPos pos, ShadowZombieNinja ninja) {
            if (!ninja.level().getBlockState(pos).isAir()) {
                net.minecraft.world.level.block.state.BlockState state = ninja.level().getBlockState(pos);
                return state.getBlock() instanceof net.minecraft.world.level.block.LeavesBlock ||
                       state.getBlock() == net.minecraft.world.level.block.Blocks.OAK_LOG ||
                       state.getBlock() == net.minecraft.world.level.block.Blocks.SPRUCE_LOG ||
                       state.getBlock() == net.minecraft.world.level.block.Blocks.BIRCH_LOG ||
                       state.getBlock() == net.minecraft.world.level.block.Blocks.JUNGLE_LOG;
            }
            return false;
        }

        /**
         * Check if block is solid (for environmental analysis)
         */
        private boolean isSolidBlock(net.minecraft.core.BlockPos pos, ShadowZombieNinja ninja) {
            net.minecraft.world.level.block.state.BlockState state = ninja.level().getBlockState(pos);
            return state.isSolidRender(ninja.level(), pos) && !state.isAir() && !state.getFluidState().isSource();
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
    
        ///////////////////////////////////////////////////////////////////////////
        // ENHANCED AI GOAL CLASSES - START
        ///////////////////////////////////////////////////////////////////////////
    
        /**
         * Advanced targeting system with priority scoring for intelligent target selection
         */
        private static class PrioritizedTargetingGoal extends Goal {
            private final ShadowZombieNinja ninja;
    
            public PrioritizedTargetingGoal(ShadowZombieNinja ninja) {
                this.ninja = ninja;
                this.setFlags(EnumSet.of(Goal.Flag.LOOK));
            }
    
            @Override
            public boolean canUse() {
                return ninja.level().isClientSide() || ninja.getTarget() == null;
            }
    
            @Override
            public void start() {
                if (ninja.level().isClientSide()) return;
    
                // Find all potential targets within detection range
                java.util.List<LivingEntity> potentialTargets = new java.util.ArrayList<>();
                ServerLevel serverLevel = (ServerLevel) ninja.level();
                if (serverLevel != null && serverLevel.getServer() != null) {
                    for (ServerPlayer player : serverLevel.getServer().getPlayerList().getPlayers()) {
                        if (ninja.distanceToSqr(player) <= TARGET_SELECTION_RADIUS * TARGET_SELECTION_RADIUS) {
                            potentialTargets.add(player);
                        }
                    }
                }
    
                // Add villagers and other mobs as secondary targets
                net.minecraft.world.phys.AABB targetArea = new net.minecraft.world.phys.AABB(
                    ninja.blockPosition().getX() - TARGET_SELECTION_RADIUS,
                    ninja.blockPosition().getY() - TARGET_SELECTION_RADIUS,
                    ninja.blockPosition().getZ() - TARGET_SELECTION_RADIUS,
                    ninja.blockPosition().getX() + TARGET_SELECTION_RADIUS,
                    ninja.blockPosition().getY() + TARGET_SELECTION_RADIUS,
                    ninja.blockPosition().getZ() + TARGET_SELECTION_RADIUS
                );
                
                for (Entity entity : ninja.level().getEntitiesOfClass(Villager.class, targetArea, 
                    entity -> ninja.distanceToSqr(entity) <= TARGET_SELECTION_RADIUS * TARGET_SELECTION_RADIUS)) {
                    potentialTargets.add((LivingEntity) entity);
                }
    
                if (potentialTargets.isEmpty()) {
                    ninja.setTarget(null);
                    return;
                }
    
                // Select highest priority target using multi-criteria scoring
                LivingEntity bestTarget = selectHighestPriorityTarget(potentialTargets);
                ninja.setTarget(bestTarget);
                 
                // Update target memory
                // Use setTarget instead of updateTargetMemory which doesn't exist
                this.ninja.setTarget(bestTarget);
            }
    
            @Override
            public boolean canContinueToUse() {
                return false;
            }
    
            /**
             * Calculate priority score for target selection using multiple weighted criteria
             */
            private double calculateTargetPriority(LivingEntity target) {
                double distanceScore = calculateDistanceScore(target);
                double threatScore = calculateThreatScore(target);
                double attackPatternScore = calculateAttackPatternScore(target);
    
                // Weighted sum of scores
                return (distanceScore * TARGET_PRIORITY_DISTANCE_WEIGHT) +
                       (threatScore * TARGET_PRIORITY_THREAT_WEIGHT) +
                       (attackPatternScore * TARGET_PRIORITY_RECENT_ATTACK_WEIGHT);
            }
    
            /**
             * Select highest priority target from list using priority scoring
             */
            private LivingEntity selectHighestPriorityTarget(java.util.List<LivingEntity> targets) {
                LivingEntity bestTarget = null;
                double highestPriority = -1.0;
    
                for (LivingEntity target : targets) {
                    double priority = calculateTargetPriority(target);
                    if (priority > highestPriority) {
                        highestPriority = priority;
                        bestTarget = target;
                    }
                }
    
                return bestTarget != null ? bestTarget : targets.get(0);
            }
    
            /**
             * Calculate score based on distance (closer = better)
             */
            private double calculateDistanceScore(LivingEntity target) {
                double distanceSqr = ninja.distanceToSqr(target);
                double maxDistanceSqr = TARGET_SELECTION_RADIUS * TARGET_SELECTION_RADIUS;
                return 1.0 - (distanceSqr / maxDistanceSqr); // Normalize to 0-1 range
            }
    
            /**
             * Calculate score based on target threat level (higher health = higher threat)
             */
            private double calculateThreatScore(LivingEntity target) {
                float health = target.getHealth();
                float maxHealth = target.getMaxHealth();
                return health / maxHealth; // Higher health = higher threat score
            }
    
            /**
             * Calculate score based on recent attack patterns (more aggressive = better target)
             */
            private double calculateAttackPatternScore(LivingEntity target) {
                java.util.List<Long> timestamps = ninja.attackPatterns.getOrDefault(target, new java.util.ArrayList<Long>());
                return timestamps.size() / 10.0; // Normalize to 0-1 range
            }
        }
    
        /**
         * Dynamic threat assessment system that adapts to target behavior
         */
        private static class ThreatAssessmentGoal extends Goal {
            private final ShadowZombieNinja ninja;
            private int assessmentInterval = 0;
            private static final int ASSESSMENT_DELAY = 60; // 3 seconds between assessments
    
            public ThreatAssessmentGoal(ShadowZombieNinja ninja) {
                this.ninja = ninja;
                this.setFlags(EnumSet.of(Goal.Flag.LOOK));
            }
    
            @Override
            public boolean canUse() {
                return ninja.getTarget() != null && !ninja.level().isClientSide();
            }
    
            @Override
            public void start() {
                this.assessmentInterval = ASSESSMENT_DELAY;
            }
    
            @Override
            public void tick() {
                if (--this.assessmentInterval <= 0) {
                    this.assessmentInterval = ASSESSMENT_DELAY;
                    assessTargetThreat();
                }
            }
    
            @Override
            public boolean canContinueToUse() {
                return ninja.getTarget() != null;
            }
    
            /**
             * Continuously assess target threat and update combat strategy
             */
            private void assessTargetThreat() {
                LivingEntity target = ninja.getTarget();
                if (target == null) return;
                
                // Update attack pattern recognition
                updateAttackPattern(target);
                
                // Adjust combat strategy based on threat assessment
                if (isHighThreatTarget(target)) {
                    ninja.getNavigation().setSpeedModifier(1.5D); // Increase speed for high threat
                    ninja.shadowKillCooldown = Math.max(0, ninja.shadowKillCooldown - 20); // Expedite ultimate
                    // High threat sequence: Ultimate -> Area Control -> Ranged -> Melee
                    prioritizeSkills(ShadowKillGoal.class, QuadShadowGoal.class, PhantomShurikenGoal.class);
                } else if (isMediumThreatTarget(target)) {
                    ninja.getNavigation().setSpeedModifier(1.3D); // Medium speed
                    ninja.quadShadowCooldown = Math.max(0, ninja.quadShadowCooldown - 10); // More frequent clones
                    // Medium threat sequence: Area Control -> Ranged -> Melee
                    prioritizeSkills(QuadShadowGoal.class, PhantomShurikenGoal.class);
                } else {
                    ninja.getNavigation().setSpeedModifier(1.2D); // Normal speed for low threat
                    ninja.phantomShurikenCooldown = Math.max(0, ninja.phantomShurikenCooldown - 10); // More frequent projectiles
                    // Low threat sequence: Ranged -> Melee
                    prioritizeSkills(PhantomShurikenGoal.class);
                }
            }
    
            /**
             * Recognize attack patterns from target behavior
             */
            private void updateAttackPattern(LivingEntity target) {
                ninja.attackPatterns.computeIfAbsent(target, k -> new java.util.ArrayList<Long>()).add((long)ninja.tickCount);
                
                // Limit pattern memory to recent attacks (last 10 seconds)
                if (ninja.patternRecognitionCounter++ >= PATTERN_RECOGNITION_WINDOW) {
                    ninja.patternRecognitionCounter = 0;
                    for (LivingEntity t : new java.util.ArrayList<>(ninja.attackPatterns.keySet())) {
                        java.util.List<Long> timestamps = ninja.attackPatterns.get(t);
                        timestamps.removeIf(timestamp -> ninja.tickCount - timestamp > 200); // Remove attacks older than 10 seconds
                        if (timestamps.isEmpty()) {
                            ninja.attackPatterns.remove(t);
                        }
                    }
                }
            }
    
            /**
             * Determine if target poses high threat based on multiple factors
             */
            private boolean isHighThreatTarget(LivingEntity target) {
                return target.getHealth() > target.getMaxHealth() * 0.7 ||
                       (target instanceof ServerPlayer player && player.getAbilities().instabuild) ||
                       ninja.distanceToSqr(target) < 9.0D;
            }
            
            /**
             * Determine if target poses medium threat based on multiple factors
             */
            private boolean isMediumThreatTarget(LivingEntity target) {
                return target.getHealth() > target.getMaxHealth() * 0.4 &&
                       target.getHealth() <= target.getMaxHealth() * 0.7;
            }
            
            /**
             * Prioritize skill usage based on threat level
             */
            private void prioritizeSkills(java.lang.Class<? extends Goal>... goalClasses) {
                // In a full implementation, this would dynamically adjust goal priorities
                // For now, we'll log the priority sequence for demonstration
                if (!ninja.level().isClientSide()) {
                    ServerLevel serverLevel = (ServerLevel) ninja.level();
                    if (serverLevel != null && serverLevel.getServer() != null) {
                        java.util.StringJoiner skillSequence = new java.util.StringJoiner(" -> ");
                        for (java.lang.Class<? extends Goal> goalClass : goalClasses) {
                            skillSequence.add(goalClass.getSimpleName());
                        }
                        
                        serverLevel.getServer().getPlayerList().getPlayers().forEach(player -> {
                            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                                "[AI] Threat response: " + skillSequence.toString()
                            ));
                        });
                    }
                }
            }
        }
    
        /**
         * Intelligent environmental adaptation for better terrain utilization
         */
        private static class EnvironmentalAdaptationGoal extends Goal {
            private final ShadowZombieNinja ninja;
            private int adaptationInterval = 0;
            private static final int ADAPTATION_DELAY = 100; // 5 seconds between adaptations
    
            public EnvironmentalAdaptationGoal(ShadowZombieNinja ninja) {
                this.ninja = ninja;
                this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
            }
    
            @Override
            public boolean canUse() {
                return ninja.getTarget() != null && !ninja.level().isClientSide();
            }
    
            @Override
            public void start() {
                this.adaptationInterval = ADAPTATION_DELAY;
            }
    
            @Override
            public void tick() {
                if (--this.adaptationInterval <= 0) {
                    this.adaptationInterval = ADAPTATION_DELAY;
                    adaptToEnvironment();
                }
            }
    
            @Override
            public boolean canContinueToUse() {
                return ninja.getTarget() != null;
            }
    
            /**
             * Adapt behavior based on current environment and terrain
             */
            private void adaptToEnvironment() {
                if (isInOpenArea()) {
                    ninja.shadowCloneDuration += 50; // Longer clone duration in open areas
                    ninja.quadShadowCooldown = Math.max(0, ninja.quadShadowCooldown - 10); // More frequent clones
                    ninja.phantomShurikenCooldown = Math.max(0, ninja.phantomShurikenCooldown + 10); // Less frequent projectiles in open areas
                } else if (isInConfinedSpace()) {
                    ninja.phantomShurikenCooldown = Math.max(0, ninja.phantomShurikenCooldown - 15); // More frequent projectiles
                    limitCloneCount(); // Fewer clones in confined spaces
                } else if (isInForest()) {
                    ninja.shadowCloneDuration += 30; // Extra clone duration in forests for cover
                    useForestCover(); // Use trees for cover
                } else if (isInCave()) {
                    ninja.quadShadowCooldown = Math.max(0, ninja.quadShadowCooldown - 10); // More frequent clones in caves
                    useCaveTerrain(); // Use cave walls for cover
                } else if (isOnHighGround()) {
                    useHeightAdvantage(); // Utilize vantage points
                }
            }
            
            /**
             * Use forest terrain for cover during combat
             */
            private void useForestCover() {
                // Prioritize teleporting to positions with tree cover
                if (!ninja.shadowClones.isEmpty()) {
                    for (int i = 0; i < ninja.shadowClones.size(); i++) {
                        Vec3 clonePos = ninja.shadowClones.get(i);
                        net.minecraft.core.BlockPos pos = new net.minecraft.core.BlockPos(
                            (int)Math.floor(clonePos.x),
                            (int)Math.floor(clonePos.y),
                            (int)Math.floor(clonePos.z)
                        );
                        if (ninja.level().getBlockState(pos.above()).getBlock() instanceof net.minecraft.world.level.block.LeavesBlock) {
                            ninja.teleportToShadowClone(i);
                            break;
                        }
                    }
                }
            }
            
            /**
             * Use cave terrain for cover during combat
             */
            private void useCaveTerrain() {
                // Prioritize teleporting to positions near cave walls for cover
                if (!ninja.shadowClones.isEmpty()) {
                    for (int i = 0; i < ninja.shadowClones.size(); i++) {
                        Vec3 clonePos = ninja.shadowClones.get(i);
                        net.minecraft.core.BlockPos pos = new net.minecraft.core.BlockPos(
                            (int)Math.floor(clonePos.x),
                            (int)Math.floor(clonePos.y),
                            (int)Math.floor(clonePos.z)
                        );
                        if (ninja.isSolidBlock(pos.north()) || ninja.isSolidBlock(pos.south()) || ninja.isSolidBlock(pos.east()) || ninja.isSolidBlock(pos.west())) {
                            ninja.teleportToShadowClone(i);
                            break;
                        }
                    }
                }
            }
    
            /**
             * Check if ninja is in open area (few obstacles)
             */
            private boolean isInOpenArea() {
                return ninja.findClearRadius(8.0) > 20 && !ninja.isInForest() && !ninja.isInCave();
            }
            
            /**
             * Check if ninja is in confined space (many obstacles)
             */
            private boolean isInConfinedSpace() {
                return ninja.findClearRadius(5.0) < 8 || ninja.isInCave();
            }
            
            /**
             * Check if ninja is in forest terrain (high tree cover)
             */
            private boolean isInForest() {
                // Check for tree cover above
                int treeCount = 0;
                for (int x = -3; x <= 3; x += 2) {
                    for (int z = -3; z <= 3; z += 2) {
                        net.minecraft.core.BlockPos pos = new net.minecraft.core.BlockPos(
                            (int)Math.floor(ninja.getX() + x),
                            (int)Math.floor(ninja.getY() + 1),
                            (int)Math.floor(ninja.getZ() + z)
                        );
                        if (ninja.level().getBlockState(pos).getBlock() instanceof net.minecraft.world.level.block.LeavesBlock) {
                            treeCount++;
                        }
                    }
                }
                return treeCount > 4;
            }
            
            /**
             * Check if ninja is in cave terrain (low light, enclosed space)
             */
            private boolean isInCave() {
                // Check for low light level and solid blocks around
                return ninja.level().getBrightness(net.minecraft.world.level.LightLayer.SKY, ninja.blockPosition()) < 8 &&
                       ninja.findClearRadius(3.0) < 10;
            }
            
            /**
             * Check if ninja is on high ground
             */
            private boolean isOnHighGround() {
                double groundY = ninja.findGroundY(ninja.getX(), ninja.getY(), ninja.getZ());
                return ninja.getY() - groundY > 3.0; // More than 3 blocks above ground
            }
            
            /**
             * Limit number of clones in confined spaces to prevent collision
            */
            private void limitCloneCount() {
                if (ninja.shadowClones.size() > 2) {
                    ninja.shadowClones.remove(ninja.shadowClones.size() - 1);
                }
            }
            
            /**
             * Use height advantage for better attack positioning
             */
            private void useHeightAdvantage() {
                LivingEntity target = ninja.getTarget();
                if (target != null && ninja.getY() > target.getY()) {
                    ninja.getLookControl().setLookAt(target, 45.0F, 45.0F);
                    
                    // Implement proper attack cooldown system from height advantage
                    // Reduce cooldowns when on high ground for more aggressive attacks
                    if (ninja.phantomShurikenCooldown > 0) {
                        ninja.phantomShurikenCooldown = Math.max(0, ninja.phantomShurikenCooldown - 15); // 15 tick reduction (0.75s)
                    }
                    if (ninja.quadShadowCooldown > 0) {
                        ninja.quadShadowCooldown = Math.max(0, ninja.quadShadowCooldown - 30); // 30 tick reduction (1.5s)
                    }
                    if (ninja.shadowKillCooldown > 0 && ninja.shadowKillPassiveStacks >= 3) {
                        ninja.shadowKillCooldown = Math.max(0, ninja.shadowKillCooldown - 60); // 60 tick reduction (3s) for ultimate
                    }
                    
                    // Increase passive stack gain when on high ground
                    if (ninja.shadowKillPassiveStacks < MAX_PASSIVE_STACKS) {
                        ninja.shadowKillPassiveStacks = Math.min(ninja.shadowKillPassiveStacks + 1, MAX_PASSIVE_STACKS);
                    }
                }
            }
        }
    
        /**
         * Predictive movement anticipation system
         */
        private static class PredictiveMovementGoal extends Goal {
            private final ShadowZombieNinja ninja;
            private int predictionInterval = 0;
            private static final int PREDICTION_DELAY = 40; // 2 seconds between predictions
    
            public PredictiveMovementGoal(ShadowZombieNinja ninja) {
                this.ninja = ninja;
                this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
            }
    
            @Override
            public boolean canUse() {
                return ninja.getTarget() != null && !ninja.level().isClientSide();
            }
    
            @Override
            public void start() {
                this.predictionInterval = PREDICTION_DELAY;
            }
    
            @Override
            public void tick() {
                if (--this.predictionInterval <= 0) {
                    this.predictionInterval = PREDICTION_DELAY;
                    predictTargetMovement();
                }
            }
    
            @Override
            public boolean canContinueToUse() {
                return ninja.getTarget() != null;
            }
    
            /**
             * Predict target movement patterns and adjust positioning accordingly
             */
            private void predictTargetMovement() {
                LivingEntity target = ninja.getTarget();
                if (target == null) return;
    
                // Update last known position
                ninja.lastKnownTargetPosition = target.position();
                ninja.lastTargetSeenTime = ninja.tickCount;
    
                // Check if target is moving predictably
                boolean isMovingPredictably = isTargetMovingPredictably(target);
                ninja.isTargetMovingPredictably = isMovingPredictably;
    
                if (isMovingPredictably) {
                    ninja.predictedTargetPosition = predictFuturePosition(target);
                    adjustPositionForPrediction();
                } else {
                    ninja.predictedTargetPosition = target.position(); // Fallback to current position
                }
            }
    
            /**
             * Determine if target is moving in predictable pattern
             */
            private boolean isTargetMovingPredictably(LivingEntity target) {
                Vec3 currentPos = target.position();
                double distanceMoved = currentPos.distanceTo(ninja.lastKnownTargetPosition);
                
                // Target is moving predictably if:
                // 1. Moving in consistent direction
                // 2. Moving at relatively constant speed
                // 3. Not changing direction erratically
                return distanceMoved > 0.1 && target.getDeltaMovement().lengthSqr() > 0.01;
            }
    
            /**
             * Predict target's future position based on current movement vector
             */
            private Vec3 predictFuturePosition(LivingEntity target) {
                Vec3 currentPos = target.position();
                Vec3 previousPos = ninja.lastKnownTargetPosition;
                Vec3 deltaMovement = target.getDeltaMovement();
                
                // Calculate target speed (blocks per tick)
                double speed = previousPos.distanceTo(currentPos);
                // Predict 0.5 to 2 seconds into future based on speed (faster targets = shorter prediction window)
                double predictionTime = Math.max(0.5, Math.min(2.0, 3.0 / speed));
                
                // If target is accelerating, adjust prediction
                Vec3 previousDelta = target.getDeltaMovement().subtract(target.getDeltaMovement().scale(0.1)); // Simplified acceleration check
                if (deltaMovement.lengthSqr() > previousDelta.lengthSqr() * 1.2) {
                    predictionTime *= 0.8; // Shorter prediction for accelerating targets
                } else if (deltaMovement.lengthSqr() < previousDelta.lengthSqr() * 0.8) {
                    predictionTime *= 1.2; // Longer prediction for decelerating targets
                }
                
                return currentPos.add(deltaMovement.scale(predictionTime));
            }
    
            /**
             * Adjust ninja position to intercept predicted target position
             */
            private void adjustPositionForPrediction() {
                if (ninja.predictedTargetPosition.equals(Vec3.ZERO)) return;
    
                double distanceToPredicted = ninja.position().distanceTo(ninja.predictedTargetPosition);
                if (distanceToPredicted > 5.0) { // Only adjust if target is reasonably far
                    ninja.getNavigation().moveTo(ninja.predictedTargetPosition.x,
                                             ninja.predictedTargetPosition.y,
                                             ninja.predictedTargetPosition.z,
                                             1.3D);
                }
            }
        }
    
        ///////////////////////////////////////////////////////////////////////////
        // ENHANCED AI GOAL CLASSES - END
        ///////////////////////////////////////////////////////////////////////////
    
        ///////////////////////////////////////////////////////////////////////////
        // HELPERS - START
        ///////////////////////////////////////////////////////////////////////////
    
        /**
         * Update target memory for pattern recognition
         */
        private void updateTargetMemory(LivingEntity target) {
            if (target == null) return;
    
            // Add to target history
            ninja.targetHistory.add(target);
    
            // Limit target history size
            while (ninja.targetHistory.size() > MAX_TARGET_MEMORY) {
                ninja.targetHistory.poll();
            }
    
            // Update current high priority target if this target has higher priority
            double currentPriority = calculateTargetPriority(target);
            double highPriority = ninja.currentHighPriorityTarget != null
                ? calculateTargetPriority(ninja.currentHighPriorityTarget)
                : -1.0;
    
            if (currentPriority > highPriority) {
                ninja.currentHighPriorityTarget = target;
            }
        }
    
        /**
         * Calculate priority score for target selection (reused from goals)
         */
        private double calculateTargetPriority(LivingEntity target) {
            double distanceScore = calculateDistanceScore(target);
            double threatScore = calculateThreatScore(target);
            double attackPatternScore = calculateAttackPatternScore(target);
            double patternRecognitionScore = calculatePatternRecognitionScore(target);
            double environmentalAdvantageScore = calculateEnvironmentalAdvantageScore(target);
    
            // Weighted sum of scores with enhanced criteria
            return (distanceScore * TARGET_PRIORITY_DISTANCE_WEIGHT) +
                   (threatScore * TARGET_PRIORITY_THREAT_WEIGHT) +
                   (attackPatternScore * TARGET_PRIORITY_RECENT_ATTACK_WEIGHT) +
                   (patternRecognitionScore * TARGET_PRIORITY_PATTERN_RECOGNITION_WEIGHT) +
                   (environmentalAdvantageScore * TARGET_PRIORITY_ENVIRONMENTAL_ADVANTAGE_WEIGHT);
        }
    
        /**
         * Calculate score based on distance (closer = better)
         */
        private double calculateDistanceScore(LivingEntity target) {
            double distanceSqr = ninja.distanceToSqr(target);
            double maxDistanceSqr = TARGET_SELECTION_RADIUS * TARGET_SELECTION_RADIUS;
            return 1.0 - (distanceSqr / maxDistanceSqr); // Normalize to 0-1 range
        }
    
        /**
         * Calculate score based on target threat level (higher health = higher threat)
         */
        private double calculateThreatScore(LivingEntity target) {
            float health = target.getHealth();
            float maxHealth = target.getMaxHealth();
            return health / maxHealth; // Higher health = higher threat score
        }
    
        /**
         * Calculate score based on recent attack patterns (more aggressive = better target)
         */
        private double calculatePatternRecognitionScore(LivingEntity target) {
            if (!(target instanceof ServerPlayer)) return 0.5; // Neutral score for non-players
            
            ServerPlayer player = (ServerPlayer) target;
            double score = 0.5; // Base score
            
            // Check for predictable movement patterns
            if (ninja.isTargetMovingPredictably) {
                score += 0.3;
            }
            
            // Check for repeated attack patterns
            java.util.List<Long> attackTimestamps = ninja.attackPatterns.getOrDefault(target, new java.util.ArrayList<Long>());
            if (attackTimestamps.size() > 5) {
                score += 0.2 * (attackTimestamps.size() / 10.0);
            }
            
            // Check for equipment that makes target more predictable
            if (player.getMainHandItem().is(Items.BOW) || player.getMainHandItem().is(Items.CROSSBOW)) {
                score += 0.1; // Ranged weapons have more predictable attack patterns
            }
            
            return Math.min(score, 1.0); // Cap at 1.0
        }
    
        private double calculateEnvironmentalAdvantageScore(LivingEntity target) {
            double score = 0.5; // Base score
            
            // Check if we have height advantage
            if (ninja.getY() > target.getY() + 1.0) {
                score += 0.3;
            }
            
            // Check if target is in exposed position - simplified for now
            if (true && !isTargetInCover(target)) {
                score += 0.2;
            }
            
            // Check if we're in favorable terrain
            if (ninja.isInForest() && !isTargetInForest(target)) {
                score += 0.2;
            } else if (ninja.isInCave() && !isTargetInCave(target)) {
                score += 0.2;
            }
            
            return Math.min(score, 1.0); // Cap at 1.0
        }
    
        private boolean isTargetInCover(LivingEntity target) {
            // Simplified cover check - check for blocks above target
            net.minecraft.core.BlockPos targetPos = target.blockPosition();
            net.minecraft.world.level.block.state.BlockState state1 = ninja.level().getBlockState(targetPos.above());
            net.minecraft.world.level.block.state.BlockState state2 = ninja.level().getBlockState(targetPos.above(2));
            return state1.isSolidRender(ninja.level(), targetPos.above()) ||
                   state2.isSolidRender(ninja.level(), targetPos.above(2));
        }
    
        private boolean isTargetInForest(LivingEntity target) {
            // Check for tree cover above target
            int treeCount = 0;
            for (int x = -2; x <= 2; x += 2) {
                for (int z = -2; z <= 2; z += 2) {
                    net.minecraft.core.BlockPos pos = new net.minecraft.core.BlockPos(
                        (int)Math.floor(target.getX() + x),
                        (int)Math.floor(target.getY() + 1),
                        (int)Math.floor(target.getZ() + z)
                    );
                    if (ninja.level().getBlockState(pos).getBlock() instanceof net.minecraft.world.level.block.LeavesBlock) {
                        treeCount++;
                    }
                }
            }
            return treeCount > 3;
        }
    
        private boolean isTargetInCave(LivingEntity target) {
            // Check if target is in cave-like conditions
            return ninja.level().getBrightness(net.minecraft.world.level.LightLayer.SKY, target.blockPosition()) < 8 &&
                   ninja.findClearRadius(3.0) < 12;
        }
    
        private double calculateAttackPatternScore(LivingEntity target) {
            java.util.List<Long> timestamps = ninja.attackPatterns.getOrDefault(target, new java.util.ArrayList<Long>());
            
            // More sophisticated pattern recognition
            if (timestamps.size() < 3) return 0.0;
            
            // Calculate attack frequency consistency
            double totalInterval = 0;
            for (int i = 1; i < timestamps.size(); i++) {
                totalInterval += timestamps.get(i) - timestamps.get(i-1);
            }
            double avgInterval = totalInterval / (timestamps.size() - 1);
            double consistency = 1.0 - Math.abs(avgInterval - 20.0) / 20.0; // 20 ticks = 1 second
            
            // Return normalized score
            return Math.min(consistency * 0.8 + 0.2, 1.0); // 80% consistency + 20% base
        }
    
        /**
         * Find number of clear blocks in given radius for environmental assessment
         */
        private int findClearRadius(double radius) {
            int clearCount = 0;
            Vec3 pos = ninja.position();
            
            for (double x = -radius; x <= radius; x += 0.5) {
                for (double z = -radius; z <= radius; z += 0.5) {
                    for (double y = -1; y <= 1; y += 0.5) { // Check small vertical range
                        Vec3 checkPos = pos.add(x, y, z);
                        if (isBlockClear(checkPos)) {
                            clearCount++;
                        }
                    }
                }
            }
            
            return clearCount;
        }
    
        /**
         * Check if block at position is clear (not solid, not liquid)
         */
        private boolean isBlockClear(Vec3 pos) {
            net.minecraft.core.BlockPos blockPos = new net.minecraft.core.BlockPos(
                (int)Math.floor(pos.x),
                (int)Math.floor(pos.y),
                (int)Math.floor(pos.z)
            );
            net.minecraft.world.level.block.state.BlockState state = ninja.level().getBlockState(blockPos);
            
            return !state.isSolidRender(ninja.level(), blockPos) && !state.getFluidState().isSource();
        }
    
        ///////////////////////////////////////////////////////////////////////////
        // HELPERS - END
        ///////////////////////////////////////////////////////////////////////////
    
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