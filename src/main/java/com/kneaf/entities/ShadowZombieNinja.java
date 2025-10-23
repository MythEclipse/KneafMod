package com.kneaf.entities;
import com.kneaf.core.OptimizationInjector;

import net.minecraft.core.BlockPos;
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
    
    // Skill constants
    private static final int PHANTOM_SHURIKEN_COOLDOWN = 60; // 3 seconds
    private static final int QUAD_SHADOW_COOLDOWN = 120; // 6 seconds
    private static final int SHADOW_KILL_COOLDOWN = 300; // 15 seconds
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
            if (shadowCloneDuration <= 0) {
                shadowClones.clear();
            }
        }
        
        // Update passive stacks decay
        if (shadowKillPassiveStacks > 0 && this.tickCount % 40 == 0) { // Decay every 2 seconds
            shadowKillPassiveStacks--;
        }
    }

    @Override
    public boolean doHurtTarget(@Nonnull Entity target) {
        boolean flag = super.doHurtTarget(target);
        if (flag && target instanceof LivingEntity livingTarget) {
            // Hayabusa passive: Shadow Kill enhanced attacks
            float baseDamage = 4.0F;
            float passiveMultiplier = 1.0f + (shadowKillPassiveStacks * 0.1f);
            float totalDamage = baseDamage * passiveMultiplier;
            
            livingTarget.hurt(this.damageSources().mobAttack(this), totalDamage);
            
            // Use Rust optimization for passive stack calculation
            shadowKillPassiveStacks = OptimizationInjector.calculatePassiveStacks(
                shadowKillPassiveStacks, true, MAX_PASSIVE_STACKS
            );
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
            .add(Attributes.MAX_HEALTH, 200.0D)
            .add(Attributes.MOVEMENT_SPEED, 0.3D)
            .add(Attributes.ATTACK_DAMAGE, 8.0D)
            .add(Attributes.FOLLOW_RANGE, 35.0D)
            .add(Attributes.ARMOR, 4.0D)
            .add(Attributes.SPAWN_REINFORCEMENTS_CHANCE, 0.0D);
    }

    // Hayabusa Skills Implementation with Rust Optimization
    
    private void performPhantomShuriken(LivingEntity target) {
        if (phantomShurikenCooldown > 0 || target == null) return;

        Vec3 targetPos = target.position();
        Vec3 currentPos = this.position();

        // Use Rust optimization for trajectory calculation
        double[] trajectory = OptimizationInjector.calculatePhantomShurikenTrajectory(
            currentPos.x, currentPos.y, currentPos.z,
            targetPos.x, targetPos.y, targetPos.z, 15.0
        );

        // Create phantom shuriken projectile with optimized trajectory
        double d0 = trajectory[0] - currentPos.x;
        double d1 = trajectory[1] - currentPos.y + 0.3333333333333333D;
        double d2 = trajectory[2] - currentPos.z;
        double d3 = Math.sqrt(d0 * d0 + d2 * d2);

        // Create shuriken that will return
        Arrow shuriken = new Arrow(this.level(), this, new ItemStack(Items.NETHERITE_SCRAP), new ItemStack(Items.IRON_SWORD));
        shuriken.setPos(this.getX(), this.getEyeY() - 0.1D, this.getZ());
        shuriken.shoot(d0, d1 + d3 * 0.2D, d2, 2.0F, 8);
        shuriken.setBaseDamage(8.0D);
        shuriken.setNoGravity(true);
        shuriken.pickup = net.minecraft.world.entity.projectile.AbstractArrow.Pickup.DISALLOWED;
        
        if (!this.level().isClientSide()) {
            ((net.minecraft.server.level.ServerLevel)this.level()).addFreshEntity(shuriken);
        }
        
        // Visual effects
        this.level().addParticle(ParticleTypes.SONIC_BOOM, this.getX(), this.getY() + 1, this.getZ(), 0.0D, 0.0D, 0.0D);
        this.playSound(SoundEvents.PLAYER_ATTACK_SWEEP, 1.0F, 1.5F);

        phantomShurikenCooldown = PHANTOM_SHURIKEN_COOLDOWN;
        
        // Schedule shuriken return after 2 seconds
        this.level().scheduleTick(this.blockPosition(), this.level().getBlockState(this.blockPosition()).getBlock(), 40);
    }
    
    private void performQuadShadow() {
        if (quadShadowCooldown > 0) return;

        Vec3 currentPos = this.position();
        
        // Use Rust optimization for shadow clone positions
        double[][] clonePositions = OptimizationInjector.calculateQuadShadowPositions(
            currentPos.x, currentPos.y, currentPos.z, 3.0
        );
        
        // Create 4 shadow clones around the ninja
        shadowClones.clear();
        for (int i = 0; i < 4; i++) {
            Vec3 clonePos = new Vec3(clonePositions[i][0], clonePositions[i][1], clonePositions[i][2]);
            shadowClones.add(clonePos);
            
            // Visual effect for clone creation
            this.level().addParticle(ParticleTypes.PORTAL, clonePos.x, clonePos.y + 1, clonePos.z, 0.0D, 0.0D, 0.0D);
        }
        
        shadowCloneDuration = SHADOW_CLONE_DURATION;
        quadShadowCooldown = QUAD_SHADOW_COOLDOWN;
        this.playSound(SoundEvents.ILLUSIONER_MIRROR_MOVE, 1.0F, 1.0F);
    }
    
    private void teleportToShadowClone(int cloneIndex) {
        if (cloneIndex < 0 || cloneIndex >= shadowClones.size()) return;
        
        Vec3 clonePos = shadowClones.get(cloneIndex);
        this.teleportTo(clonePos.x, clonePos.y, clonePos.z);
        
        // Visual effects
        this.level().addParticle(ParticleTypes.EXPLOSION, this.getX(), this.getY() + 1, this.getZ(), 0.0D, 0.0D, 0.0D);
        this.playSound(SoundEvents.ENDERMAN_TELEPORT, 1.0F, 1.0F);
        
        // Clear clones after teleport
        shadowClones.clear();
        shadowCloneDuration = 0;
    }
    
    private void performShadowKill(LivingEntity target) {
        if (shadowKillCooldown > 0 || target == null) return;

        // Use Rust optimization for damage calculation
        double optimizedDamage = OptimizationInjector.calculateShadowKillDamage(shadowKillPassiveStacks, 20.0);
        
        // Apply damage
        target.hurt(this.damageSources().mobAttack(this), (float) optimizedDamage);
        
        // Consume all passive stacks
        shadowKillPassiveStacks = 0;
        
        // Visual effects
        this.level().addParticle(ParticleTypes.DRAGON_BREATH, target.getX(), target.getY() + 1, target.getZ(), 0.0D, 0.0D, 0.0D);
        this.level().addParticle(ParticleTypes.SWEEP_ATTACK, this.getX(), this.getY() + 1, this.getZ(), 0.0D, 0.0D, 0.0D);
        this.playSound(SoundEvents.PLAYER_ATTACK_CRIT, 1.0F, 0.5F);
        
        shadowKillCooldown = SHADOW_KILL_COOLDOWN;
    }

    private void performRangedAttack(LivingEntity target) {
        double d0 = target.getX() - this.getX();
        double d1 = target.getY(0.3333333333333333D) - this.getY();
        double d2 = target.getZ() - this.getZ();
        double d3 = Math.sqrt(d0 * d0 + d2 * d2);

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
        private int attackTimer = 0;

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
            this.attackTimer = 10; // 0.5 seconds charge time
        }

        @Override
        public void tick() {
            LivingEntity target = ninja.getTarget();
            if (target != null) {
                ninja.getLookControl().setLookAt(target, 30.0F, 30.0F);
                if (--this.attackTimer <= 0) {
                    ninja.performPhantomShuriken(target);
                    this.stop();
                }
            }
        }

        @Override
        public boolean canContinueToUse() {
            return this.attackTimer > 0;
        }
    }
    
    private static class QuadShadowGoal extends Goal {
        private final ShadowZombieNinja ninja;
        private int teleportTimer = 0;

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
            this.teleportTimer = 20; // 1 second
        }

        @Override
        public void tick() {
            if (--this.teleportTimer <= 0) {
                ninja.performQuadShadow();
                // Randomly teleport to one of the clones
                if (!ninja.shadowClones.isEmpty()) {
                    int randomClone = ninja.random.nextInt(ninja.shadowClones.size());
                    ninja.teleportToShadowClone(randomClone);
                }
                this.stop();
            }
        }

        @Override
        public boolean canContinueToUse() {
            return this.teleportTimer > 0;
        }
    }
    
    private static class ShadowKillGoal extends Goal {
        private final ShadowZombieNinja ninja;
        private int ultimateTimer = 0;

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
            this.ultimateTimer = 15; // 0.75 seconds
        }

        @Override
        public void tick() {
            LivingEntity target = ninja.getTarget();
            if (target != null) {
                ninja.getLookControl().setLookAt(target, 30.0F, 30.0F);
                if (--this.ultimateTimer <= 0) {
                    ninja.performShadowKill(target);
                    this.stop();
                }
            }
        }

        @Override
        public boolean canContinueToUse() {
            return this.ultimateTimer > 0;
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