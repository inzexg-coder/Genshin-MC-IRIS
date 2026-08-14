package net.teyvat.entity;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.ai.goal.LookAroundGoal;
import net.minecraft.entity.ai.goal.LookAtEntityGoal;
import net.minecraft.entity.ai.goal.SwimGoal;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.DustParticleEffect;
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
import net.teyvat.particle.TeyvatParticles;
import net.teyvat.progression.MobLevels;
import net.teyvat.server.SlimeTraining;
import net.teyvat.server.WikiDiscoveries;
import net.teyvat.wiki.TeyvatWiki;

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
    /** Тики до следующего выстрела водяным шаром. */
    private int shootCooldown = 30;
    /** Владелец тренировочного слайма (null — обычный слайм мира). */
    private UUID ownerUuid;
    /** Бой включён: до объявления задания слайм неуязвим даже для владельца. */
    private boolean combatReady;
    /** Тик анимации смерти: -1 = жив, 0+ = распад в воду. Синхронизируется на клиент. */
    private static final TrackedData<Integer> DEATH_ANIM = DataTracker.registerData(
            HydroSlimeEntity.class, TrackedDataHandlerRegistry.INTEGER);
    /** Длительность анимации распада в тиках (набухание → взрыв). */
    public static final int DEATH_ANIM_TOTAL = 16;

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
    protected void initDataTracker(DataTracker.Builder builder) {
        super.initDataTracker(builder);
        builder.add(DEATH_ANIM, -1);
    }

    /** Тик анимации смерти (-1 — слайм жив). */
    public int getDeathAnimTicks() {
        return this.dataTracker.get(DEATH_ANIM);
    }

    @Override
    protected void initGoals() {
        this.goalSelector.add(1, new SwimGoal(this));
        this.goalSelector.add(2, new HydroSlimeHopGoal(this));
        this.goalSelector.add(3, new LookAtEntityGoal(this, PlayerEntity.class, 10.0f));
        this.goalSelector.add(4, new LookAroundGoal(this));
    }

    @Override
    public void tick() {
        if (this.getDeathAnimTicks() >= 0) {
            // Распад в воду: тело замирает и набухает, эффекты крутит сервер.
            if (this.getEntityWorld() instanceof ServerWorld serverWorld) {
                this.tickDeathAnimation(serverWorld, this.getDeathAnimTicks());
            }
            return;
        }
        super.tick();
        if (!this.getEntityWorld().isClient()) {
            this.hopCooldown--;
            ServerWorld serverWorld = (ServerWorld) this.getEntityWorld();
            // Цель: владелец (тренировка) или ближайший игрок поблизости.
            LivingEntity target = this.getTarget();
            if (target == null || !target.isAlive()) {
                target = this.findTarget(serverWorld);
                if (target != null) {
                    this.setTarget(target);
                }
            }
            if (target != null && target.isAlive()) {
                // Слайм всегда смотрит на свою цель (глазами к игроку), а не летит боком.
                double dx = target.getX() - this.getX();
                double dz = target.getZ() - this.getZ();
                float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0f);
                this.setYaw(yaw);
                this.setHeadYaw(yaw);
                this.setBodyYaw(yaw);
                // Стрельба водяными шарами по цели в радиусе видимости.
                if ((!this.isTraining() || this.combatReady)
                        && this.squaredDistanceTo(target) <= 20.0 * 20.0
                        && --this.shootCooldown <= 0) {
                    if (this.getVisibilityCache().canSee(target)) {
                        this.shootAt(target);
                    }
                    this.shootCooldown = 30 + this.random.nextInt(20);
                }
            }
        }
    }

    /** Ищет цель: владельца тренировочного слайма, иначе ближайшего игрока. */
    private LivingEntity findTarget(ServerWorld world) {
        if (this.ownerUuid != null) {
            ServerPlayerEntity owner = world.getServer().getPlayerManager().getPlayer(this.ownerUuid);
            if (owner != null && owner.isAlive()) {
                return owner;
            }
        }
        LivingEntity nearest = null;
        double best = 20.0 * 20.0;
        for (ServerPlayerEntity player : world.getPlayers()) {
            if (!player.isAlive() || player.isSpectator()) {
                continue;
            }
            double d = this.squaredDistanceTo(player);
            if (d < best) {
                best = d;
                nearest = player;
            }
        }
        return nearest;
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
        // Важно: спавним с собственным типом сущности, иначе клиент рендерит
        // ванильный снежок (конструктор SnowballEntity(World, owner, stack)
        // жёстко ставит EntityType.SNOWBALL).
        HydroSlimeProjectileEntity projectile = new HydroSlimeProjectileEntity(HydroSlimeProjectileEntity.TYPE, world);
        projectile.setOwner(this);
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
            // Вики: первый поверженный слайм открывает запись «Гидро слайм».
            if (damageSource.getAttacker() instanceof ServerPlayerEntity killer) {
                WikiDiscoveries.discover(killer, TeyvatWiki.ID_HYDRO_SLIME);
            }
            if (this.ownerUuid != null) {
                SlimeTraining.onSlimeKilled(serverWorld, this);
            }
            // Слайм замирает, вскипает пузырями, набухает — и взрывается фонтаном воды.
            this.setVelocity(Vec3d.ZERO);
            this.dataTracker.set(DEATH_ANIM, 0);
            Vec3d p = new Vec3d(this.getX(), this.getY(), this.getZ());
            serverWorld.spawnParticles(ParticleTypes.BUBBLE, p.x, p.y + 0.4, p.z,
                    18, 0.6, 0.6, 0.6, 0.12);
            serverWorld.playSound(null, this.getBlockPos(),
                    SoundEvents.BLOCK_BUBBLE_COLUMN_UPWARDS_AMBIENT,
                    SoundCategory.HOSTILE, 0.8f, 1.4f);
        }
        super.onDeath(damageSource);
    }

    /** Кадр анимации распада: тело набухает, затем взрыв в фонтан воды. */
    private void tickDeathAnimation(ServerWorld world, int tick) {
        Vec3d p = new Vec3d(this.getX(), this.getY(), this.getZ());
        if (tick == 4) {
            // Набухание: мелкие брызги срываются с тела, по земле расходится первая рябь.
            world.spawnParticles(TeyvatParticles.WATER_DROPLET, p.x, p.y + 0.4, p.z,
                    12, 0.0, 0.0, 0.0, 0.0);
            world.spawnParticles(TeyvatParticles.WATER_RIPPLE, p.x, p.y + 0.05, p.z,
                    1, 0.0, 0.0, 0.0, 0.0);
        } else if (tick == 8) {
            // Тело набухает: пузыри вскипают, брызги бьют выше.
            world.spawnParticles(TeyvatParticles.WATER_DROPLET, p.x, p.y + 0.6, p.z,
                    18, 0.0, 0.0, 0.0, 0.0);
            world.spawnParticles(ParticleTypes.BUBBLE, p.x, p.y + 0.4, p.z,
                    16, 0.6, 0.6, 0.6, 0.1);
        } else if (tick == 12) {
            // Перед взрывом: тело на пределе, пузыри и брызги вокруг.
            world.spawnParticles(TeyvatParticles.WATER_DROPLET, p.x, p.y + 0.7, p.z,
                    22, 0.0, 0.0, 0.0, 0.0);
            world.spawnParticles(ParticleTypes.BUBBLE, p.x, p.y + 0.4, p.z,
                    20, 0.7, 0.7, 0.7, 0.12);
            world.spawnParticles(TeyvatParticles.WATER_MIST, p.x, p.y + 0.4, p.z,
                    6, 0.0, 0.0, 0.0, 0.0);
        }
        int next = tick + 1;
        this.dataTracker.set(DEATH_ANIM, next);
        if (next >= DEATH_ANIM_TOTAL) {
            this.burst(world, p);
            this.discard();
        }
    }

    /** Взрыв слайма: кольца, фонтан, радиальные брызги, пузыри, дымка и звук. */
    private void burst(ServerWorld world, Vec3d p) {
        // Два кольца-всплеска и тройная рябь на земле.
        world.spawnParticles(TeyvatParticles.WATER_SPLASH, p.x, p.y + 0.55, p.z,
                1, 0.0, 0.0, 0.0, 0.0);
        world.spawnParticles(TeyvatParticles.WATER_SPLASH, p.x, p.y + 0.75, p.z,
                1, 0.0, 0.0, 0.0, 0.0);
        world.spawnParticles(TeyvatParticles.WATER_RIPPLE, p.x, p.y + 0.05, p.z,
                3, 0.3, 0.0, 0.3, 0.0);
        // Фонтан: три яруса брызг, взлетают вверх и падают дугой.
        world.spawnParticles(TeyvatParticles.WATER_DROPLET, p.x, p.y + 0.15, p.z,
                26, 0.0, 0.0, 0.0, 0.0);
        world.spawnParticles(TeyvatParticles.WATER_DROPLET, p.x, p.y + 0.5, p.z,
                30, 0.0, 0.0, 0.0, 0.0);
        world.spawnParticles(TeyvatParticles.WATER_DROPLET, p.x, p.y + 0.95, p.z,
                24, 0.0, 0.0, 0.0, 0.0);
        // Радиальные брызги во все стороны (скорость каждой задаёт серверный пакет).
        world.spawnParticles(TeyvatParticles.WATER_DROPLET, p.x, p.y + 0.5, p.z,
                34, 1.1, 0.6, 1.1, 0.6);
        // Гарантированно видимый ванильный всплеск: брызги, пузыри и хлопки.
        world.spawnParticles(ParticleTypes.SPLASH, p.x, p.y + 0.4, p.z,
                34, 1.0, 0.7, 1.0, 0.4);
        world.spawnParticles(ParticleTypes.BUBBLE, p.x, p.y + 0.4, p.z,
                30, 1.0, 0.8, 1.0, 0.2);
        world.spawnParticles(ParticleTypes.BUBBLE_POP, p.x, p.y + 0.7, p.z,
                16, 0.7, 0.5, 0.7, 0.08);
        // Световая водяная вспышка (видна при любом шейдере).
        world.spawnParticles(new DustParticleEffect(0xFF9ADBFF, 1.4f),
                p.x, p.y + 0.5, p.z, 36, 1.0, 0.7, 1.0, 0.0);
        // Дымка довершает всплеск.
        world.spawnParticles(TeyvatParticles.WATER_MIST, p.x, p.y + 0.4, p.z,
                16, 0.0, 0.0, 0.0, 0.0);
        world.playSound(null, this.getBlockPos(), SoundEvents.ENTITY_GENERIC_SPLASH,
                SoundCategory.HOSTILE, 1.4f, 0.9f);
        world.playSound(null, this.getBlockPos(), SoundEvents.ENTITY_GENERIC_SPLASH,
                SoundCategory.HOSTILE, 1.0f, 1.4f);
        world.playSound(null, this.getBlockPos(), SoundEvents.BLOCK_BUBBLE_COLUMN_UPWARDS_AMBIENT,
                SoundCategory.HOSTILE, 0.8f, 1.6f);
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
