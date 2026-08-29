package net.teyvat.entity;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.entity.ai.goal.EscapeDangerGoal;
import net.minecraft.entity.ai.goal.FleeEntityGoal;
import net.minecraft.entity.ai.goal.LookAroundGoal;
import net.minecraft.entity.ai.goal.LookAtEntityGoal;
import net.minecraft.entity.ai.goal.SwimGoal;
import net.minecraft.entity.ai.goal.WanderAroundGoal;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;

/**
 * Синяя рогатая ящерица (Blue Horned Lizard) из Genshin: пассивное животное,
 * похожее на ванильную ящерицу. Бродит по земле, при приближении игрока —
 * в панике убегает (как в оригинале ловля ящериц). На него не действуют
 * голод/спавн-яйца агрессии; чисто декоративная дикая живность.
 */
public class BlueHornedLizardEntity extends PathAwareEntity {
    private static final Identifier TYPE_ID = Identifier.of("teyvat", "blue_horned_lizard");

    public static final EntityType<BlueHornedLizardEntity> TYPE = Registry.register(
            Registries.ENTITY_TYPE, TYPE_ID,
            EntityType.Builder.create(BlueHornedLizardEntity::new, SpawnGroup.CREATURE)
                    .dimensions(0.5f, 0.45f)
                    .maxTrackingRange(48)
                    .build(RegistryKey.of(RegistryKeys.ENTITY_TYPE, TYPE_ID)));

    public static void register() {
        if (TYPE == null) {
            throw new IllegalStateException("BlueHornedLizard entity type not registered");
        }
    }

    public static DefaultAttributeContainer createAttributes() {
        return PathAwareEntity.createMobAttributes()
                .add(EntityAttributes.MAX_HEALTH, 8.0)
                .add(EntityAttributes.MOVEMENT_SPEED, 0.3)
                .add(EntityAttributes.FOLLOW_RANGE, 8.0)
                .build();
    }

    public BlueHornedLizardEntity(EntityType<? extends BlueHornedLizardEntity> type, World world) {
        super(type, world);
        this.experiencePoints = 0;
    }

    @Override
    protected void initGoals() {
        // Ящерица не плавает добровольно — SwimGoal нет, чтобы не уходила в воду.
        this.goalSelector.add(0, new EscapeDangerGoal(this, 1.25));
        // Убегает от игрока: дикая, пугливая зверушка.
        this.goalSelector.add(1, new FleeEntityGoal<>(this, PlayerEntity.class, 6.0f, 1.2, 0.9));
        this.goalSelector.add(2, new WanderAroundGoal(this, 0.8));
        this.goalSelector.add(3, new LookAtEntityGoal(this, PlayerEntity.class, 6.0f));
        this.goalSelector.add(4, new LookAroundGoal(this));
    }
    @Override
    protected void dropLoot(ServerWorld world, DamageSource damageSource, boolean causedByPlayer) {
        super.dropLoot(world, damageSource, causedByPlayer);
        // Ящерица дропает хвост (как в Genshin).
        this.dropStack(world, new ItemStack(net.teyvat.item.TeyvatItems.LIZARD_TAIL));
    }
}
