package com.kneaf.entities;
import com.kneaf.core.OptimizationInjector;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerBossEvent;
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
import net.minecraft.world.entity.player.Player;
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
    private static final int QUAD_SHADOW_COOLDOWN = 180; // 9 seconds - Skill 2 
    private static final int SHADOW_KILL_COOLDOWN = 600; // 30 seconds - Ultimate
    private static final int MAX_PASSIVE_STACKS = 4;
    private static final float PASSIVE_DAMAGE_MULTIPLIER = 1.2f;
    
    // Shadow clones for Quad Shadow skill
    private final java.util.List<Vec3> shadowClones = new java.util.ArrayList<>();
    private int shadowCloneDuration = 0;
    private static final int SHADOW_CLONE_DURATION = 200; // 10 seconds

    public ShadowZombieNinja(EntityType<? extends Zombie> entityType, Level level) {
        super(entityType, level);
        this.xpReward = 50;
        this.setHealth(this.getMaxHealth());
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(1, new PhantomShurikenGoal(this));
        this.goalSelector.addGoal(2, new QuadShadowGoal(this));
        this.goalSelector.addGoal(3, new ShadowKillGoal(this));
        this.goalSelector.addGoal(4, new MeleeAttackGoal(this, 1.2D, false));
        this.goalSelector.addGoal(5, new MoveTowardsTargetGoal(this, 1.2D, 32.0F));
        this.goalSelector.addGoal(6, new RandomStrollGoal(this, 1.0D));
        this.goalSelector.addGoal(7, new RandomLookAroundGoal(this));

        this.targetSelector.addGoal(1, new HurtByTargetGoal(this));
        this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Player.class, true));
        this.targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(this, Villager.class, true));
    }

    @Override
    public void tick() {
        super.tick();
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
            
            // Continuous particle effects for active shadow clones
            for (Vec3 clonePos : shadowClones) {
                // Add particles every tick to make clones visible
                for (int i = 0; i < 3; i++) {
                    double offsetX = (this.random.nextDouble() - 0.5) * 0.5;
                    double offsetY = this.random.nextDouble() * 2.0;
                    double offsetZ = (this.random.nextDouble() - 0.5) * 0.5;
                    
                    this.level().addParticle(ParticleTypes.PORTAL, 
                        clonePos.x + offsetX, clonePos.y + offsetY, clonePos.z + offsetZ, 
                        0.0D, 0.0D, 0.0D);
                    this.level().addParticle(ParticleTypes.WITCH, 
                        clonePos.x + offsetX, clonePos.y + offsetY, clonePos.z + offsetZ, 
                        0.0D, 0.0D, 0.0D);
                }
                
                // Occasional flash effect
                if (this.tickCount % 10 == 0) {
                    this.level().addParticle(ParticleTypes.ENCHANTED_HIT, 
                        clonePos.x, clonePos.y + 1, clonePos.z, 
                        0.0D, 0.0D, 0.0D);
                }
            }
            
            if (shadowCloneDuration <= 0) {
                // Fade out effect when clones disappear
                for (Vec3 clonePos : shadowClones) {
                    for (int i = 0; i < 15; i++) {
                        double offsetX = (this.random.nextDouble() - 0.5) * 1.0;
                        double offsetY = this.random.nextDouble() * 2.0;
                        double offsetZ = (this.random.nextDouble() - 0.5) * 1.0;
                        this.level().addParticle(ParticleTypes.SMOKE, 
                            clonePos.x + offsetX, clonePos.y + offsetY, clonePos.z + offsetZ, 
                            0.0D, 0.05D, 0.0D);
                    }
                }
                shadowClones.clear();
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
        
        // Massive visual effect at starting position
        for (int i = 0; i < 30; i++) {
            double offsetX = (this.random.nextDouble() - 0.5) * 2.0;
            double offsetY = this.random.nextDouble() * 2.0;
            double offsetZ = (this.random.nextDouble() - 0.5) * 2.0;
            this.level().addParticle(ParticleTypes.PORTAL, 
                currentPos.x + offsetX, currentPos.y + offsetY, currentPos.z + offsetZ, 
                0.0D, 0.1D, 0.0D);
            this.level().addParticle(ParticleTypes.SMOKE, 
                currentPos.x + offsetX, currentPos.y + offsetY, currentPos.z + offsetZ, 
                0.0D, 0.05D, 0.0D);
        }
        
        // Use Rust optimization for shadow clone positions
        double[][] clonePositions = OptimizationInjector.calculateQuadShadowPositions(
            currentPos.x, currentPos.y, currentPos.z, 3.0
        );
        
        // Create 4 shadow clones around the ninja with massive effects
        shadowClones.clear();
        for (int i = 0; i < 4; i++) {
            Vec3 clonePos = new Vec3(clonePositions[i][0], clonePositions[i][1], clonePositions[i][2]);
            shadowClones.add(clonePos);
            
            // Multiple particle effects for each clone
            for (int j = 0; j < 20; j++) {
                double offsetX = (this.random.nextDouble() - 0.5) * 1.0;
                double offsetY = this.random.nextDouble() * 2.0;
                double offsetZ = (this.random.nextDouble() - 0.5) * 1.0;
                
                this.level().addParticle(ParticleTypes.PORTAL, 
                    clonePos.x + offsetX, clonePos.y + offsetY, clonePos.z + offsetZ, 
                    0.0D, 0.0D, 0.0D);
                this.level().addParticle(ParticleTypes.ENCHANT, 
                    clonePos.x + offsetX, clonePos.y + offsetY, clonePos.z + offsetZ, 
                    0.0D, 0.0D, 0.0D);
                this.level().addParticle(ParticleTypes.WITCH, 
                    clonePos.x + offsetX, clonePos.y + offsetY, clonePos.z + offsetZ, 
                    0.0D, 0.0D, 0.0D);
            }
            
            // Big explosion-like effect at clone position
            this.level().addParticle(ParticleTypes.EXPLOSION, 
                clonePos.x, clonePos.y + 1, clonePos.z, 
                0.0D, 0.0D, 0.0D);
            this.level().addParticle(ParticleTypes.SOUL_FIRE_FLAME, 
                clonePos.x, clonePos.y + 1, clonePos.z, 
                0.0D, 0.1D, 0.0D);
        }
        
        shadowCloneDuration = SHADOW_CLONE_DURATION;
        quadShadowCooldown = QUAD_SHADOW_COOLDOWN;
        this.playSound(SoundEvents.ILLUSIONER_MIRROR_MOVE, 1.0F, 1.0F);
        this.playSound(SoundEvents.ENDER_DRAGON_FLAP, 1.0F, 0.8F);
        this.playSound(SoundEvents.WITHER_SPAWN, 0.3F, 2.0F);
    }
    
    private void teleportToShadowClone(int cloneIndex) {
        if (cloneIndex < 0 || cloneIndex >= shadowClones.size()) return;
        
        Vec3 oldPos = this.position();
        Vec3 clonePos = shadowClones.get(cloneIndex);
        
        // Massive effects at departure position
        for (int i = 0; i < 25; i++) {
            double offsetX = (this.random.nextDouble() - 0.5) * 1.5;
            double offsetY = this.random.nextDouble() * 2.0;
            double offsetZ = (this.random.nextDouble() - 0.5) * 1.5;
            this.level().addParticle(ParticleTypes.EXPLOSION, 
                oldPos.x + offsetX, oldPos.y + offsetY, oldPos.z + offsetZ, 
                0.0D, 0.0D, 0.0D);
            this.level().addParticle(ParticleTypes.LARGE_SMOKE, 
                oldPos.x + offsetX, oldPos.y + offsetY, oldPos.z + offsetZ, 
                0.0D, 0.1D, 0.0D);
            this.level().addParticle(ParticleTypes.SOUL_FIRE_FLAME, 
                oldPos.x + offsetX, oldPos.y + offsetY, oldPos.z + offsetZ, 
                0.0D, 0.0D, 0.0D);
        }
        
        // Teleport
        this.teleportTo(clonePos.x, clonePos.y, clonePos.z);
        
        // Massive effects at arrival position
        for (int i = 0; i < 25; i++) {
            double offsetX = (this.random.nextDouble() - 0.5) * 1.5;
            double offsetY = this.random.nextDouble() * 2.0;
            double offsetZ = (this.random.nextDouble() - 0.5) * 1.5;
            this.level().addParticle(ParticleTypes.EXPLOSION, 
                this.getX() + offsetX, this.getY() + offsetY, this.getZ() + offsetZ, 
                0.0D, 0.0D, 0.0D);
            this.level().addParticle(ParticleTypes.PORTAL, 
                this.getX() + offsetX, this.getY() + offsetY, this.getZ() + offsetZ, 
                0.0D, 0.0D, 0.0D);
            this.level().addParticle(ParticleTypes.ENCHANTED_HIT, 
                this.getX() + offsetX, this.getY() + offsetY, this.getZ() + offsetZ, 
                0.0D, 0.0D, 0.0D);
        }
        
        // Multiple sound effects
        this.playSound(SoundEvents.ENDERMAN_TELEPORT, 1.0F, 1.0F);
        this.playSound(SoundEvents.ENDER_DRAGON_FLAP, 1.0F, 1.5F);
        this.playSound(SoundEvents.LIGHTNING_BOLT_IMPACT, 0.5F, 1.8F);
        
        // Clear clones after teleport
        shadowClones.clear();
        shadowCloneDuration = 0;
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
            // Instant teleport - no timer
            ninja.performQuadShadow();
            // Randomly teleport to one of the clones immediately
            if (!ninja.shadowClones.isEmpty()) {
                int randomClone = ninja.random.nextInt(ninja.shadowClones.size());
                ninja.teleportToShadowClone(randomClone);
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