package net.teyvat.entity;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.entity.projectile.thrown.SnowballEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.teyvat.particle.TeyvatParticles;

/**
 * Водяной шар Гидро слайма: медленный снаряд с гравитацией,
 * на попадании наносит урон и разлетается брызгами (только эффект).
 */
public class HydroSlimeProjectileEntity extends SnowballEntity {
    private static final Identifier TYPE_ID = Identifier.of("teyvat", "hydro_slime_projectile");

    public static final EntityType<HydroSlimeProjectileEntity> TYPE = Registry.register(
            Registries.ENTITY_TYPE, TYPE_ID,
            EntityType.Builder.create((EntityType<HydroSlimeProjectileEntity> type, World world) -> new HydroSlimeProjectileEntity(type, world), SpawnGroup.MISC)
                    .dimensions(0.3f, 0.3f)
                    .disableSaving()
                    .maxTrackingRange(64)
                    .build(RegistryKey.of(RegistryKeys.ENTITY_TYPE, TYPE_ID)));

    public static void register() {
        if (TYPE == null) {
            throw new IllegalStateException("HydroSlimeProjectile entity type not registered");
        }
    }

    public HydroSlimeProjectileEntity(EntityType<? extends HydroSlimeProjectileEntity> type, World world) {
        super(type, world);
    }

    /** Лёгкий водяной след за летящей сферой: капли и дымка. */
    @Override
    public void tick() {
        super.tick();
        if (this.getEntityWorld() instanceof ServerWorld serverWorld && this.age % 3 == 0) {
            Vec3d pos = new Vec3d(this.getX(), this.getY(), this.getZ());
            serverWorld.spawnParticles(TeyvatParticles.WATER_MIST, pos.x, pos.y, pos.z,
                    1, 0.0, 0.0, 0.0, 0.0);
            serverWorld.spawnParticles(TeyvatParticles.WATER_DROPLET, pos.x, pos.y, pos.z,
                    1, 0.0, 0.0, 0.0, 0.0);
        }
    }

    @Override
    protected void onCollision(HitResult hitResult) {
        if (hitResult.getType() == HitResult.Type.ENTITY && hitResult instanceof EntityHitResult entityHit) {
            Entity target = entityHit.getEntity();
            Entity owner = this.getOwner();
            if (target != owner && target instanceof LivingEntity living) {
                if (this.getEntityWorld() instanceof ServerWorld serverWorld) {
                    LivingEntity attacker = owner instanceof LivingEntity le ? le : null;
                    if (attacker != null) {
                        living.damage(serverWorld,
                                serverWorld.getDamageSources().mobProjectile(this, attacker), 3.0f);
                    }
                }
            }
        }
        if (this.getEntityWorld() instanceof ServerWorld serverWorld) {
            Vec3d p = new Vec3d(this.getX(), this.getY(), this.getZ());
            // Всплеск при попадании: рябь, брызги и дымка, как при смерти слайма, но меньше.
            serverWorld.spawnParticles(TeyvatParticles.WATER_RIPPLE, p.x, p.y, p.z,
                    1, 0.0, 0.0, 0.0, 0.0);
            serverWorld.spawnParticles(TeyvatParticles.WATER_DROPLET, p.x, p.y, p.z,
                    7, 0.0, 0.0, 0.0, 0.0);
            serverWorld.spawnParticles(TeyvatParticles.WATER_MIST, p.x, p.y, p.z,
                    2, 0.0, 0.0, 0.0, 0.0);
            serverWorld.spawnParticles(ParticleTypes.SPLASH, p.x, p.y, p.z,
                    8, 0.3, 0.3, 0.3, 0.05);
            serverWorld.playSound(null, this.getBlockPos(), SoundEvents.ENTITY_GENERIC_SPLASH,
                    SoundCategory.HOSTILE, 0.9f, 1.5f);
            this.discard();
        }
    }
}
