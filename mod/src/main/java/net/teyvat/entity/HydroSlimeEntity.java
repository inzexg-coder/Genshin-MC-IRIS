package net.teyvat.entity;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.ai.goal.LookAroundGoal;
import net.minecraft.entity.ai.goal.LookAtEntityGoal;
import net.minecraft.entity.ai.goal.SwimGoal;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.teyvat.item.TeyvatItems;
import net.teyvat.progression.MobLevels;
import net.teyvat.server.SlimeTraining;

import java.util.UUID;

/**
 * Водный слайм из Genshin: голубое полупрозрачное тело с каплей-короной.
 * Прыгает как слайм, на расстоянии выплёвывает водяной шар (HydroSlimeProjectileEntity).
 * Не разделяется при смерти — вместо этого всплеск воды (только эффект).
 */
public class HydroSlimeEntity extends HostileEntity {
    private static final Identifier TYPE_ID = Identifier.of("teyvat", "hydro_slime");

    public static final EntityType<HydroSlimeEntity> TYPE = Registry.register(
            Registries.ENTITY_TYPE, TYPE_ID,
            EntityType.Builder.create(HydroSlimeEntity::new, SpawnGroup.MONSTER)
                    .dimensions(1.2f, 1.1f)
                    .maxTrackingRange(64)
                    .build(RegistryKey.of(RegistryKeys.ENTITY_TYPE, TYPE_ID)));

    /** Тики до следующего прыжка (считаем на сервере). */
    private int hopCooldown = 20;
    /** Владелец тренировочного слайма (null — обычный слайм мира). */
    private UUID ownerUuid;
    /** Бой включён: до объявления задания слайм неуязвим даже для владельца. */
    private boolean combatReady;

    public static void register() {
        if (TYPE == null) {
            throw new IllegalStateException("HydroSlime entity type not registered");
        }
    }

    public static DefaultAttributeContainer createAttributes() {
        return HostileEntity.createHostileAttributes()
                .add(EntityAttributes.MAX_HEALTH, 14.0)
                .add(EntityAttributes.MOVEMENT_SPEED, 0.22)
                .add(EntityAttributes.ATTACK_DAMAGE, 2.0)
                .add(EntityAttributes.FOLLOW_RANGE, 18.0)
                .build();
    }

    public HydroSlimeEntity(EntityType<? extends HydroSlimeEntity> type, World world) {
        super(type, world);
        this.experiencePoints = 0; // опыт персонажа выдаёт система Teyvat (MobXp)
    }

    @Override
    protected void initGoals() {
        this.goalSelector.add(1, new SwimGoal(this));
        this.goalSelector.add(2, new HydroSlimeShootGoal(this));
        this.goalSelector.add(3, new HydroSlimeHopGoal(this));
        this.goalSelector.add(4, new LookAtEntityGoal(this, PlayerEntity.class, 10.0f));
        this.goalSelector.add(5, new LookAroundGoal(this));
    }

    @Override
    public void tick() {
        super.tick();
        if (!this.getEntityWorld().isClient()) {
            this.hopCooldown--;
            // Тренировочный слайм всегда прыгает к своему владельцу.
            if (this.ownerUuid != null && this.getEntityWorld() instanceof ServerWorld serverWorld) {
                ServerPlayerEntity owner = serverWorld.getServer().getPlayerManager().getPlayer(this.ownerUuid);
                if (owner != null && owner.isAlive()) {
                    this.setTarget(owner);
                }
            }
        }
    }

    /** Тренировочный слайм: принадлежит игроку и бьётся только им. */
    public boolean isTraining() {
        return this.ownerUuid != null;
    }

    public UUID getOwnerUuid() {
        return this.ownerUuid;
    }

    public void setOwnerUuid(UUID ownerUuid) {
        this.ownerUuid = ownerUuid;
    }

    public boolean isCombatReady() {
        return this.combatReady;
    }

    public void setCombatReady(boolean combatReady) {
        this.combatReady = combatReady;
    }

    @Override
    protected void writeCustomData(WriteView nbt) {
        super.writeCustomData(nbt);
        if (this.ownerUuid != null) {
            nbt.putString("Owner", this.ownerUuid.toString());
        }
        nbt.putBoolean("CombatReady", this.combatReady);
    }

    @Override
    protected void readCustomData(ReadView nbt) {
        super.readCustomData(nbt);
        String owner = nbt.getString("Owner", "");
        if (!owner.isEmpty()) {
            try {
                this.ownerUuid = UUID.fromString(owner);
            } catch (IllegalArgumentException ignored) {
                this.ownerUuid = null;
            }
        }
        this.combatReady = nbt.getBoolean("CombatReady", false);
    }

    /** Тренировочный слайм бьётся только своим владельцем и только после
     *  объявления задания (combatReady). Остальные игроки его не трогают. */
    @Override
    public boolean damage(ServerWorld world, DamageSource source, float amount) {
        if (this.ownerUuid != null) {
            Entity attacker = source.getAttacker() != null ? source.getAttacker() : source.getSource();
            if (!(attacker instanceof PlayerEntity player)
                    || !player.getUuid().equals(this.ownerUuid)
                    || !this.combatReady) {
                return false;
            }
        }
        return super.damage(world, source, amount);
    }

    /** Прыжок: к цели, если она есть, иначе лёгкий случайный подскок. */
    void hop() {
        LivingEntity target = this.getTarget();
        double speed = this.getAttributeValue(EntityAttributes.MOVEMENT_SPEED);
        if (target != null) {
            Vec3d dir = target.getEyePos().subtract(this.getEyePos()).multiply(1.0, 0.0, 1.0).normalize();
            this.setVelocity(dir.x * speed * 2.4, 0.45, dir.z * speed * 2.4);
        } else {
            this.setVelocity((this.random.nextDouble() * 2.0 - 1.0) * speed,
                    0.4,
                    (this.random.nextDouble() * 2.0 - 1.0) * speed);
        }
        this.getJumpControl().setActive();
    }

    /** Выстрел водяным шаром в цель. */
    void shootAt(LivingEntity target) {
        World world = this.getEntityWorld();
        HydroSlimeProjectileEntity projectile = new HydroSlimeProjectileEntity(world, this);
        Vec3d eye = this.getEyePos();
        projectile.setPosition(eye.x, eye.y - 0.15, eye.z);
        Vec3d aim = target.getEyePos().subtract(new Vec3d(projectile.getX(), projectile.getY(), projectile.getZ())).normalize().multiply(0.62);
        aim = aim.add((this.random.nextDouble() - 0.5) * 0.08,
                (this.random.nextDouble() - 0.5) * 0.08,
                (this.random.nextDouble() - 0.5) * 0.08);
        projectile.setVelocity(aim);
        world.spawnEntity(projectile);
        world.playSound(null, this.getBlockPos(), SoundEvents.ENTITY_SLIME_SQUISH,
                SoundCategory.HOSTILE, 0.8f, 1.5f);
    }

    @Override
    protected void dropLoot(ServerWorld world, DamageSource damageSource, boolean causedByPlayer) {
        int level = MobLevels.getLevel(this);
        if (level >= 60) {
            this.dropStack(world, new ItemStack(TeyvatItems.SLIME_CONCENTRATE, 1 + this.random.nextInt(2)));
        } else if (level >= 40) {
            this.dropStack(world, new ItemStack(TeyvatItems.SLIME_SECRETIONS, 1 + this.random.nextInt(3)));
        } else {
            this.dropStack(world, new ItemStack(TeyvatItems.SLIME_CONDENSATE, 1 + this.random.nextInt(4)));
        }
        this.dropStack(world, new ItemStack(TeyvatItems.MORA, 1 + this.random.nextInt(3)));
    }

    @Override
    public void onDeath(DamageSource damageSource) {
        if (this.getEntityWorld() instanceof ServerWorld serverWorld) {
            if (this.ownerUuid != null) {
                SlimeTraining.onSlimeKilled(serverWorld, this);
            }
            Vec3d p = new Vec3d(this.getX(), this.getY(), this.getZ());
            serverWorld.spawnParticles(ParticleTypes.SPLASH, p.x, p.y + 0.5, p.z,
                    26, 0.45, 0.45, 0.45, 0.06);
            serverWorld.playSound(null, this.getBlockPos(), SoundEvents.ENTITY_GENERIC_SPLASH,
                    SoundCategory.HOSTILE, 1.0f, 1.2f);
        }
        super.onDeath(damageSource);
    }

    /** Стрельба водяными шарами по игроку в радиусе видимости. */
    private static class HydroSlimeShootGoal extends Goal {
        private final HydroSlimeEntity slime;
        private int cooldown = 30;

        HydroSlimeShootGoal(HydroSlimeEntity slime) {
            this.slime = slime;
        }

        @Override
        public boolean canStart() {
            if (this.slime.isTraining()) {
                return false;
            }
            LivingEntity target = this.slime.getTarget();
            return target != null && target.isAlive()
                    && this.slime.squaredDistanceTo(target) <= 16.0 * 16.0;
        }

        @Override
        public void tick() {
            LivingEntity target = this.slime.getTarget();
            if (target == null || !target.isAlive()) {
                return;
            }
            this.slime.getLookControl().lookAt(target, 20.0f, 20.0f);
            if (--this.cooldown <= 0) {
                if (this.slime.getVisibilityCache().canSee(target)) {
                    this.slime.shootAt(target);
                    this.cooldown = 45 + this.slime.random.nextInt(25);
                } else {
                    this.cooldown = 10;
                }
            }
        }
    }

    /** Прыжки: каждый раз, когда слайм на земле и кулдаун истёк. */
    private static class HydroSlimeHopGoal extends Goal {
        private final HydroSlimeEntity slime;

        HydroSlimeHopGoal(HydroSlimeEntity slime) {
            this.slime = slime;
        }

        @Override
        public boolean canStart() {
            return this.slime.isOnGround() && this.slime.hopCooldown <= 0;
        }

        @Override
        public void start() {
            this.slime.hop();
            this.slime.hopCooldown = 12 + this.slime.random.nextInt(24);
        }
    }
}
