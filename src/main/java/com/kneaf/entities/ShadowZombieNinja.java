package com.kneaf.entities;

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
        net.minecraft.network.chat.Component.literal("Shadow Zombie Ninja"),
        BossEvent.BossBarColor.PURPLE,
        BossEvent.BossBarOverlay.PROGRESS
    );

    private int blinkCooldown = 0;
    private static final int BLINK_COOLDOWN_TIME = 100; // 5 seconds at 20 TPS

    public ShadowZombieNinja(EntityType<? extends Zombie> entityType, Level level) {
        super(entityType, level);
        this.xpReward = 50;
        this.setHealth(this.getMaxHealth());
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(1, new BlinkGoal(this));
        this.goalSelector.addGoal(2, new MeleeAttackGoal(this, 1.2D, false));
        this.goalSelector.addGoal(3, new MoveTowardsTargetGoal(this, 1.2D, 32.0F));
        this.goalSelector.addGoal(4, new RangedAttackGoal(this));
        this.goalSelector.addGoal(5, new RandomStrollGoal(this, 1.0D));
        this.goalSelector.addGoal(6, new RandomLookAroundGoal(this));

        this.targetSelector.addGoal(1, new HurtByTargetGoal(this));
        this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Player.class, true));
        this.targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(this, Villager.class, true));
    }

    @Override
    public void tick() {
        super.tick();
        if (blinkCooldown > 0) {
            blinkCooldown--;
        }
    }

    @Override
    public boolean doHurtTarget(@Nonnull Entity target) {
        boolean flag = super.doHurtTarget(target);
        if (flag && target instanceof LivingEntity livingTarget) {
            // Ninja melee attack - extra damage
            livingTarget.hurt(this.damageSources().mobAttack(this), 4.0F);
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

    private void performBlink() {
        if (this.blinkCooldown > 0 || this.getTarget() == null) return;

        LivingEntity target = this.getTarget();
        if (target == null) return;
        Vec3 targetPos = target.position();
        Vec3 currentPos = this.position();

        // Find a position near the target but behind them
        Vec3 direction = targetPos.subtract(currentPos).normalize();
        Vec3 blinkPos = targetPos.add(direction.scale(-3.0D)).add(0, 1, 0);

        // Ensure the position is valid
        BlockPos pos = new BlockPos((int)blinkPos.x, (int)blinkPos.y, (int)blinkPos.z);
        if (this.level().getBlockState(pos).isAir() &&
            this.level().getBlockState(pos.above()).isAir()) {
            this.teleportTo(blinkPos.x, blinkPos.y, blinkPos.z);

            // Visual effects
            this.level().addParticle(ParticleTypes.SMOKE, this.getX(), this.getY() + 1, this.getZ(), 0.0D, 0.0D, 0.0D);
            this.level().addParticle(ParticleTypes.SMOKE, pos.getX() + 0.5, pos.getY() + 1.5, pos.getZ() + 0.5, 0.0D, 0.0D, 0.0D);
            this.playSound(SoundEvents.ENDERMAN_TELEPORT, 1.0F, 1.0F);

            this.blinkCooldown = BLINK_COOLDOWN_TIME;
        }
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

    private static class BlinkGoal extends Goal {
        private final ShadowZombieNinja ninja;
        private int blinkTimer = 0;

        public BlinkGoal(ShadowZombieNinja ninja) {
            this.ninja = ninja;
            this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            return ninja.getTarget() != null && ninja.blinkCooldown == 0;
        }

        @Override
        public void start() {
            this.blinkTimer = 40; // 2 seconds
        }

        @Override
        public void tick() {
            if (--this.blinkTimer <= 0) {
                ninja.performBlink();
                this.stop();
            }
        }

        @Override
        public boolean canContinueToUse() {
            return this.blinkTimer > 0;
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