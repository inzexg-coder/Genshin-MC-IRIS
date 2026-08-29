package net.teyvat.entity;

import net.fabricmc.fabric.api.biome.v1.BiomeModifications;
import net.fabricmc.fabric.api.biome.v1.BiomeSelectors;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.entity.SpawnLocationTypes;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.SpawnRestriction;
import net.minecraft.entity.ai.goal.EscapeDangerGoal;
import net.minecraft.entity.ai.goal.FleeEntityGoal;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.ai.NoPenaltyTargeting;
import net.minecraft.entity.ai.goal.LookAroundGoal;
import net.minecraft.entity.ai.goal.LookAtEntityGoal;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.TypeFilter;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.Heightmap;
import net.minecraft.world.ServerWorldAccess;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;

import java.util.EnumSet;

/**
 * Синяя рогатая ящерица (Blue Horned Lizard) из Genshin: пугливое пассивное
 * животное. Спавнится только на Драконьем хребте, довольно редко (макс. ~3
 * особи в радиусе). Почти всегда в движении — никогда не стоит на месте,
 * замирает только прячась под деревом. При приближении игрока убегает,
 * с другими ящерицами не встречается.
 */
public class BlueHornedLizardEntity extends PathAwareEntity {
    private static final Identifier TYPE_ID = Identifier.of("teyvat", "blue_horned_lizard");
    private static final Identifier DRAGON_RIDGE = Identifier.of("teyvat", "dragon_ridge");

    // --- Настраиваемые параметры поведения ---
    /** Максимум особей в радиусе MAX_SPAWN_AREA (чтобы «максимум 3, очень редко»). */
    private static final int MAX_NEARBY = 2;
    /** Радиус (блоки), в котором считаем соседних ящериц при спавне. */
    private static final double SPAWN_MAX_AREA = 64.0;
    /** Минимальная дистанция до другой ящерицы при спавне (не встречаться). */
    private static final double SPAWN_SEPARATION = 24.0;
    /** Яндекс сплавный вес (редкость) в биоме. */
    private static final int SPAWN_WEIGHT = 2;

    // --- Обнаружение игрока ---
    /** Радиус обнаружения игрока: стал больше (4 не срабатывало — игрок
     *  оказывался почти вплотную). Рывок игрока всё равно догоняет ящерицу. */
    private static final float PLAYER_FLEE_RADIUS = 8.0f;
    private static final double PLAYER_FLEE_FAST = 1.9; // скорость паники (рывок игрока всё равно догонит)
    private static final double PLAYER_FLEE_SLOW = 1.4;

    // --- Другие ящерицы (не встречаются) ---
    private static final float LIZARD_FLEE_RADIUS = 10.0f;
    private static final double LIZARD_FLEE_FAST = 1.5;
    private static final double LIZARD_FLEE_SLOW = 1.1;

    // --- Прятанье под деревьями ---
    private static final double HIDE_PLAYER_RANGE = 12.0;  // игрок рядом (8-12) — прячемся
    private static final double HIDE_TOO_CLOSE = 3.0;      // совсем близко — не прячемся, убегаем
    private static final int HIDE_SCAN_RADIUS = 8;         // радиус поиска дерева
    private static final int HIDE_SCAN_STEP = 2;
    private static final int HIDE_FREEZE_MIN = 60;         // сколько замираем под деревом
    private static final int HIDE_FREEZE_MAX = 120;
    private static final int HIDE_DESIRE_INTERVAL = 200;   // как часто ящерица сама хочет отдохнуть

    // --- Непрерывное движение ---
    private static final double WANDER_SPEED = 0.9;
    private static final double WANDER_MIN = 4.0;
    private static final double WANDER_MAX = 14.0;

    public static final EntityType<BlueHornedLizardEntity> TYPE = Registry.register(
            Registries.ENTITY_TYPE, TYPE_ID,
            EntityType.Builder.create(BlueHornedLizardEntity::new, SpawnGroup.CREATURE)
                    .dimensions(0.5f, 0.45f)
                    .maxTrackingRange(48)
                    .build(RegistryKey.of(RegistryKeys.ENTITY_TYPE, TYPE_ID)));

    /** Сколько тиков ещё прячемся под деревом (0 — не прячемся). */
    private int hideTicks = 0;
    /** Когда ящерица снова захочет отдохнуть под деревом (возраст тика). */
    private long nextRestDesire = 0;

    public static void register() {
        if (TYPE == null) {
            throw new IllegalStateException("BlueHornedLizard entity type not registered");
        }
        // Спавн только на Драконьем хребте, редко (вес 4), в одиночку.
        BiomeModifications.addSpawn(
                BiomeSelectors.includeByKey(RegistryKey.of(RegistryKeys.BIOME, DRAGON_RIDGE)),
                SpawnGroup.CREATURE, TYPE, SPAWN_WEIGHT, 1, 1);
        // Ограничения спавна: твёрдая земля, не вода, макс. ~3 рядом,
        // не спавниться рядом с другой ящерицей.
        SpawnRestriction.register(TYPE,
                SpawnLocationTypes.ON_GROUND,
                Heightmap.Type.MOTION_BLOCKING_NO_LEAVES,
                BlueHornedLizardEntity::canSpawnNaturally);
    }

    private static boolean canSpawnNaturally(EntityType<BlueHornedLizardEntity> type,
                                             ServerWorldAccess world, SpawnReason reason,
                                             BlockPos pos, Random random) {
        // Только на суше, не в воде.
        if (!world.getBlockState(pos).getFluidState().isEmpty()) {
            return false;
        }
        var below = world.getBlockState(pos.down());
        if (!below.isOpaqueFullCube() && !below.isSolid()) {
            return false;
        }
        ServerWorld server = world.toServerWorld();
        Box nearBox = new Box(pos).expand(SPAWN_SEPARATION);
        // Не спавниться прямо рядом с другой ящерицей.
        if (!server.getEntitiesByType(TypeFilter.instanceOf(BlueHornedLizardEntity.class),
                nearBox, BlueHornedLizardEntity::isAlive).isEmpty()) {
            return false;
        }
        // Максимум ~3 особи в большом радиусе.
        Box areaBox = new Box(pos).expand(SPAWN_MAX_AREA);
        int count = server.getEntitiesByType(TypeFilter.instanceOf(BlueHornedLizardEntity.class),
                areaBox, BlueHornedLizardEntity::isAlive).size();
        return count < MAX_NEARBY;
    }

    public static DefaultAttributeContainer createAttributes() {
        return PathAwareEntity.createMobAttributes()
                .add(EntityAttributes.MAX_HEALTH, 8.0)
                .add(EntityAttributes.MOVEMENT_SPEED, 0.34)
                .add(EntityAttributes.FOLLOW_RANGE, 8.0)
                .build();
    }

    public BlueHornedLizardEntity(EntityType<? extends BlueHornedLizardEntity> type, World world) {
        super(type, world);
        this.experiencePoints = 0;
    }

    @Override
    protected void initGoals() {
        // 0 — паника (огонь и т.п.).
        this.goalSelector.add(0, new EscapeDangerGoal(this, 1.25));
        // 1 — разбегаться с другими ящерицами (не встречаются).
        this.goalSelector.add(1, new FleeEntityGoal<>(this, BlueHornedLizardEntity.class,
                LIZARD_FLEE_RADIUS, LIZARD_FLEE_FAST, LIZARD_FLEE_SLOW));
        // 2 — убегать от игрока. Надёжный гол: бежит прочь, даже если путь не строится.
        this.goalSelector.add(2, new FleePlayerGoal(this, PLAYER_FLEE_RADIUS,
                PLAYER_FLEE_FAST, PLAYER_FLEE_SLOW));
        // 3 — прятаться под деревом (при угрозе с умеренной дистанции или сама).
        this.goalSelector.add(3, new HideUnderTreeGoal(this));
        // 4 — непрерывное движение, никогда не стоит на месте.
        this.goalSelector.add(4, new RestlessWanderGoal(this, WANDER_SPEED));
        // 5 — смотрит по сторонам.
        this.goalSelector.add(5, new LookAtEntityGoal(this, PlayerEntity.class, 6.0f));
        this.goalSelector.add(6, new LookAroundGoal(this));
    }

    @Override
    public void tick() {
        super.tick();
        if (hideTicks > 0) {
            hideTicks--;
        }
    }

    boolean isHiding() {
        return hideTicks > 0;
    }

    @Override
    protected void dropLoot(ServerWorld world, DamageSource damageSource, boolean causedByPlayer) {
        super.dropLoot(world, damageSource, causedByPlayer);
        // Ящерица дропает хвост (как в Genshin).
        this.dropStack(world, new ItemStack(net.teyvat.item.TeyvatItems.LIZARD_TAIL));
    }

    // ------------------------------------------------------------------
    // Гол: убегание от игрока. Надёжнее ванильного FleeEntityGoal:
    // если не удаётся построить путь — разворачивается и бежит прочь напрямую.
    // ------------------------------------------------------------------
    private static class FleePlayerGoal extends Goal {
        private final BlueHornedLizardEntity lizard;
        private final float radius;
        private final double fastSpeed;
        private final double slowSpeed;
        private PlayerEntity player;

        FleePlayerGoal(BlueHornedLizardEntity lizard, float radius, double fastSpeed, double slowSpeed) {
            this.lizard = lizard;
            this.radius = radius;
            this.fastSpeed = fastSpeed;
            this.slowSpeed = slowSpeed;
            setControls(EnumSet.of(Control.MOVE));
        }

        @Override
        public boolean canStart() {
            player = lizard.getEntityWorld().getClosestPlayer(lizard, radius);
            return player != null && player.isAlive();
        }

        @Override
        public boolean shouldContinue() {
            if (player == null || !player.isAlive()) {
                return false;
            }
            // Продолжаем, пока игрок в разумной близости (≈3 радиуса).
            return lizard.squaredDistanceTo(player) < (radius * 3.0) * (radius * 3.0);
        }

        @Override
        public void tick() {
            if (player == null || !player.isAlive()) {
                return;
            }
            double distSq = lizard.squaredDistanceTo(player);
            if (distSq >= radius * radius) {
                return;
            }
            // Пытаемся убежать по маршруту от игрока (поиск в большом радиусе).
            Vec3d target = NoPenaltyTargeting.findFrom(lizard, 24, 10, player.getEntityPos());
            if (target != null) {
                double dist = Math.sqrt(distSq);
                double speed = dist < radius * 0.5 ? fastSpeed : slowSpeed;
                lizard.getNavigation().startMovingTo(target.x, target.y, target.z, speed);
                return;
            }
            // Путь не нашёлся — разворачиваемся от игрока и бежим напрямую.
            Vec3d away = lizard.getEntityPos().subtract(player.getEntityPos());
            if (away.lengthSquared() < 1.0E-6) {
                away = new Vec3d(1.0, 0.0, 0.0);
            }
            away = away.normalize();
            float yaw = (float) Math.toDegrees(Math.atan2(-away.x, away.z));
            lizard.setYaw(yaw);
            lizard.setBodyYaw(yaw);
            lizard.setHeadYaw(yaw);
            lizard.getNavigation().stop();
            double speed = Math.sqrt(distSq) < radius * 0.5 ? fastSpeed : slowSpeed;
            lizard.setMovementSpeed((float) speed);
            lizard.getMoveControl().strafeTo(1.0f, 0.0f);
        }

        @Override
        public void stop() {
            player = null;
        }
    }

    // ------------------------------------------------------------------
    // Гол: непрерывное блуждание (никогда не стоит на месте, кроме прятанья)
    // ------------------------------------------------------------------
    private static class RestlessWanderGoal extends Goal {
        private final PathAwareEntity mob;
        private final double speed;
        private int retryCooldown = 0;

        RestlessWanderGoal(PathAwareEntity mob, double speed) {
            this.mob = mob;
            this.speed = speed;
            setControls(EnumSet.of(Control.MOVE));
        }

        @Override
        public boolean canStart() {
            // Не бродим, пока прячемся под деревом.
            if (mob instanceof BlueHornedLizardEntity lizard && lizard.isHiding()) {
                return false;
            }
            // Если простаиваем — хотим двигаться.
            return mob.getNavigation().isIdle();
        }

        @Override
        public boolean shouldContinue() {
            return !mob.getNavigation().isIdle();
        }

        @Override
        public void start() {
            pickNewTarget();
        }

        @Override
        public void tick() {
            if (mob.getNavigation().isIdle() && --retryCooldown <= 0) {
                pickNewTarget();
            }
        }

        private void pickNewTarget() {
            retryCooldown = 20;
            for (int attempt = 0; attempt < 12; attempt++) {
                double angle = mob.getRandom().nextDouble() * Math.PI * 2.0;
                double dist = WANDER_MIN + mob.getRandom().nextDouble() * (WANDER_MAX - WANDER_MIN);
                int x = mob.getBlockX() + (int) Math.round(Math.cos(angle) * dist);
                int z = mob.getBlockZ() + (int) Math.round(Math.sin(angle) * dist);
                int groundY = mob.getEntityWorld().getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
                if (mob.getNavigation().startMovingTo(x + 0.5, groundY, z + 0.5, speed)) {
                    return;
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Гол: прятаться под деревьями (замираем на месте)
    // ------------------------------------------------------------------
    private static class HideUnderTreeGoal extends Goal {
        private final BlueHornedLizardEntity lizard;
        private boolean foundTree = false;

        HideUnderTreeGoal(BlueHornedLizardEntity lizard) {
            this.lizard = lizard;
            setControls(EnumSet.of(Control.MOVE));
        }

        @Override
        public boolean canStart() {
            if (lizard.isHiding()) {
                return false;
            }
            if (lizard.age % 10 != 0) {
                return false;
            }
            PlayerEntity player = lizard.getEntityWorld().getClosestPlayer(lizard, HIDE_TOO_CLOSE);
            if (player != null) {
                return false; // совсем близко — не прячемся, побежим (FleeEntityGoal обработает)
            }
            if (lizard.age % HIDE_DESIRE_INTERVAL == 0) {
                return true; // сама захотела отдохнуть
            }
            return lizard.getEntityWorld().getClosestPlayer(lizard, HIDE_PLAYER_RANGE) != null;
        }

        @Override
        public boolean shouldContinue() {
            return !lizard.getNavigation().isIdle() || lizard.isHiding();
        }

        @Override
        public void start() {
            foundTree = findTreeAndRun();
            if (!foundTree) {
                lizard.hideTicks = 0;
            }
        }

        @Override
        public void stop() {
            // После прибытия под дерево — замираем на некоторое время.
            if (foundTree && lizard.getNavigation().isIdle()) {
                lizard.hideTicks = HIDE_FREEZE_MIN + lizard.getRandom().nextInt(HIDE_FREEZE_MAX - HIDE_FREEZE_MIN);
            }
            foundTree = false;
        }

        @Override
        public void tick() {
            // Уже стоим под деревом — просто ждём (freeze).
        }

        private boolean findTreeAndRun() {
            int radius = HIDE_SCAN_RADIUS;
            int step = HIDE_SCAN_STEP;
            for (int dx = -radius; dx <= radius; dx += step) {
                for (int dz = -radius; dz <= radius; dz += step) {
                    if (dx == 0 && dz == 0) {
                        continue;
                    }
                    int x = lizard.getBlockX() + dx;
                    int z = lizard.getBlockZ() + dz;
                    if (!hasLeavesAbove(x, z)) {
                        continue;
                    }
                    int groundY = lizard.getEntityWorld().getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
                    BlockPos spot = new BlockPos(x, groundY, z);
                    // Место, где встанем, должно быть проходимым (не дерево-блок) и без воды.
                    var stand = lizard.getEntityWorld().getBlockState(spot);
                    if (stand.isOpaqueFullCube() || !stand.getFluidState().isEmpty()) {
                        continue;
                    }
                    if (lizard.getNavigation().startMovingTo(x + 0.5, groundY, z + 0.5, 0.9)) {
                        return true;
                    }
                }
            }
            return false;
        }

        private boolean hasLeavesAbove(int x, int z) {
            for (int y = lizard.getBlockY() + 3; y < lizard.getBlockY() + 20; y++) {
                if (lizard.getEntityWorld().getBlockState(new BlockPos(x, y, z)).isIn(BlockTags.LEAVES)) {
                    return true;
                }
            }
            return false;
        }
    }
}
