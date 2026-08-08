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

    public HydroSlimeProjectileEntity(World world, LivingEntity owner) {
        super(world, owner, net.minecraft.item.ItemStack.EMPTY);
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
            serverWorld.spawnParticles(ParticleTypes.SPLASH, p.x, p.y, p.z,
                    10, 0.25, 0.25, 0.25, 0.05);
            serverWorld.playSound(null, this.getBlockPos(), SoundEvents.ENTITY_GENERIC_SPLASH,
                    SoundCategory.HOSTILE, 0.8f, 1.5f);
            this.discard();
        }
    }
}
