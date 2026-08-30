package net.teyvat.worldgen;

import net.minecraft.block.Blocks;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.gen.feature.DefaultFeatureConfig;
import net.minecraft.world.gen.feature.Feature;
import net.minecraft.world.gen.feature.util.FeatureContext;
import net.teyvat.TeyvatMod;

/** Тропа: ЛЁГКИЙ фича-генератор. Ровность тропы обеспечивает сама density-функция
 *  (шум вдоль тропы выключен, профиль константен), поэтому здесь мы только
 *  подменяем поверхностные блоки на dirt_path. НИКАКИХ тяжёлых циклов по getTopY
 *  и выравнивания — именно они вызывали бесконечную "Загрузку территории". */
public final class StarfallValleyTrailFeature extends Feature<DefaultFeatureConfig> {
    public static final Identifier ID =
            Identifier.of(TeyvatMod.MOD_ID, "starfall_valley_trail");

    public StarfallValleyTrailFeature() {
        super(DefaultFeatureConfig.CODEC);
    }

    public static void register() {
        Registry.register(Registries.FEATURE, ID, new StarfallValleyTrailFeature());
    }

    /** Детерминированный псевдорандом 0..1 по координатам (стабилен между рестартами). */
    private static double pseudoRandom(int x, int z, long seed) {
        long hash = seed;
        hash = hash * 31 + x;
        hash = hash * 31 + z;
        hash ^= hash >>> 13;
        hash *= 0x5DEECE66DL;
        hash ^= hash >>> 8;
        return (hash & 0xFFFF) / 65536.0;
    }

    /** Камешек вдоль тропы: выбор породы (булыжник чаще, затем андезит/гранит/диорит). */
    private static net.minecraft.block.BlockState pebbleBlockFor(int x, int z) {
        double r = pseudoRandom(x, z, 4242L);
        if (r < 0.35) return Blocks.COBBLESTONE.getDefaultState();
        if (r < 0.60) return Blocks.ANDESITE.getDefaultState();
        if (r < 0.80) return Blocks.GRANITE.getDefaultState();
        return Blocks.DIORITE.getDefaultState();
    }

    @Override
    public boolean generate(FeatureContext<DefaultFeatureConfig> context) {
        ChunkPos chunkPos = new ChunkPos(context.getOrigin());
        if (!TeyvatStarfallValley.chunkMayContainTrail(chunkPos.getStartX(), chunkPos.getStartZ(),
                chunkPos.getEndX(), chunkPos.getEndZ())) {
            return false;
        }

        var world = context.getWorld();
        boolean changed = false;

        int minX = chunkPos.getStartX();
        int maxX = chunkPos.getEndX();
        int minZ = chunkPos.getStartZ();
        int maxZ = chunkPos.getEndZ();

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                double fade = TeyvatStarfallValley.trailFadeFactor(x, z);
                if (fade <= -0.3) continue;

                int surfaceY = world.getTopY(Heightmap.Type.WORLD_SURFACE, x, z);
                BlockPos surface = new BlockPos(x, surfaceY, z);
                var block = world.getBlockState(surface).getBlock();
                if (block == Blocks.GRASS_BLOCK || block == Blocks.SAND
                        || block == Blocks.SHORT_GRASS || block == Blocks.TALL_GRASS) {
                    setBlockState(world, surface, Blocks.DIRT_PATH.getDefaultState());
                    // Камешки вдоль тропы: редкие «втопленные» фрагменты камня вместо
                    // dirt_path — читается как каменистая дорога, не мешает ходьбе
                    // (ровный блок, без бугорка). Детерминированно по позиции.
                    if (pseudoRandom(x, z, 1337L) < 0.035) {
                        setBlockState(world, surface, pebbleBlockFor(x, z));
                    }
                    changed = true;
                }
            }
        }

        // Точка телепортации: строим прямо при генерации содержащих её чанков.
        // Центр (TRAILHEAD) стоит на границе двух чанков по X, поэтому каждая
        // половина ромба-платформы кладётся своим чанком — никаких записей в
        // соседние чанки, никакого замедления генерации.
        if (chunkPos.x == TeyvatStarfallValley.TRAILHEAD_X >> 4
                && chunkPos.z == TeyvatStarfallValley.TRAILHEAD_Z >> 4) {
            changed |= placeTeleportInChunk(context, TeyvatStarfallValley.TRAILHEAD_X, TeyvatStarfallValley.TRAILHEAD_Z, true);
        } else if (chunkMayContainTeleportPlatform(chunkPos.getStartX(), chunkPos.getStartZ(),
                chunkPos.getEndX(), chunkPos.getEndZ())) {
            changed |= placeTeleportInChunk(context, TeyvatStarfallValley.TRAILHEAD_X, TeyvatStarfallValley.TRAILHEAD_Z, false);
        }

        return changed;
    }

    /** Содержит ли чанк хотя бы одну ячейку расчистки вокруг точки телепортации. */
    private static boolean chunkMayContainTeleportPlatform(int minX, int minZ, int maxX, int maxZ) {
        int x = TeyvatStarfallValley.TRAILHEAD_X;
        int z = TeyvatStarfallValley.TRAILHEAD_Z;
        return x - 6 <= maxX && x + 6 >= minX && z - 6 <= maxZ && z + 6 >= minZ;
    }

    private boolean placeTeleportInChunk(FeatureContext<DefaultFeatureConfig> context, int x, int z, boolean withColumn) {
        var world = context.getWorld();
        ChunkPos chunkPos = new ChunkPos(context.getOrigin());
        int cMinX = chunkPos.getStartX();
        int cMinZ = chunkPos.getStartZ();
        int cMaxX = cMinX + 15;
        int cMaxZ = cMinZ + 15;
        boolean changed = false;

        // Единый уровень платформы: высота поверхности в центре (трайлхед) - 1.
        // ВСЕ части точки кладутся относительно этого одного уровня, чтобы ничто
        // не поднималось отдельно из-за неровной земли вокруг.
        // Платформа лежит ПОВЕРХ земли (на y поверхности, а не под травой):
        // нижний ромб кладём на уровень верхнего блока травы, чтобы он был
        // виден как «на земле», а не закопанным. Единый уровень на весь ромб.
        int centerY = world.getTopY(Heightmap.Type.WORLD_SURFACE, x, z);
        if (centerY < world.getBottomY() + 1) {
            for (int dx = -1; dx <= 1 && centerY < world.getBottomY() + 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (Math.abs(dx) + Math.abs(dz) > 1) continue;
                    int px = x + dx;
                    int pz = z + dz;
                    if (px < cMinX || px > cMaxX || pz < cMinZ || pz > cMaxZ) continue;
                    int ty = world.getTopY(Heightmap.Type.WORLD_SURFACE, px, pz);
                    if (ty > centerY) centerY = ty;
                }
            }
        }

        // Ромб радиусом 2 (|dx|+|dz| <= 2): платформа из ТОЛСТОЙ каменной кладки
        // на едином уровне centerY. Внутри неё — крест из 5 блоков тонкой резьбы.
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (Math.abs(dx) + Math.abs(dz) > 2) continue;
                int px = x + dx;
                int pz = z + dz;
                if (px < cMinX || px > cMaxX || pz < cMinZ || pz > cMaxZ) continue;
                setBlockState(world, new BlockPos(px, centerY, pz), net.teyvat.TeyvatBlocks.TELEPORT_PATH.getDefaultState());
                changed = true;
            }
        }

        // Крест из 5 блоков (|dx|+|dz| <= 1): центр + 4 стороны — ТОНКАЯ резьба.
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (Math.abs(dx) + Math.abs(dz) > 1) continue;
                int px = x + dx;
                int pz = z + dz;
                if (px < cMinX || px > cMaxX || pz < cMinZ || pz > cMaxZ) continue;
                setBlockState(world, new BlockPos(px, centerY, pz), net.teyvat.TeyvatBlocks.TELEPORT_PATH_THIN.getDefaultState());
                changed = true;
            }
        }

        // Расчистка ПОЛА вокруг платформы (радиус 6): весь тeppейн выше единого
        // уровня centerY убираем в воздух, чтобы посторонние блоки (трава/земля/
        // горки) не поднимали части точки отдельно. Платформа остаётся ровной.
        for (int dx = -6; dx <= 6; dx++) {
            for (int dz = -6; dz <= 6; dz++) {
                if (Math.abs(dx) + Math.abs(dz) > 8) continue;
                int px = x + dx;
                int pz = z + dz;
                if (px < cMinX || px > cMaxX || pz < cMinZ || pz > cMaxZ) continue;
                int localTop = world.getTopY(Heightmap.Type.WORLD_SURFACE, px, pz);
                if (localTop > centerY) {
                    // Убираем ВСЁ выше единого уровня платформы — включая ячейки
                    // самой платформы, чтобы посторонние блоки не поднимали её части
                    // отдельно друг от друга. Блоки платформы на centerY остаются.
                    for (int dy = centerY + 1; dy <= Math.min(localTop, centerY + 10); dy++) {
                        setBlockState(world, new BlockPos(px, dy, pz), net.minecraft.block.Blocks.AIR.getDefaultState());
                    }
                    changed = true;
                }
            }
        }

        // Центр: плита-полублок лежит на кресте, над ней колонна.
        if (withColumn && x >= cMinX && x <= cMaxX && z >= cMinZ && z <= cMaxZ) {
            setBlockState(world, new BlockPos(x, centerY + 1, z), net.teyvat.TeyvatBlocks.TELEPORT_SLAB_RED.getDefaultState());
            setBlockState(world, new BlockPos(x, centerY + 2, z), net.teyvat.TeyvatBlocks.TELEPORT_COLUMN_BASE_RED.getDefaultState());
            setBlockState(world, new BlockPos(x, centerY + 3, z), net.teyvat.TeyvatBlocks.TELEPORT_COLUMN_SHAFT_RED.getDefaultState());
            setBlockState(world, new BlockPos(x, centerY + 4, z), net.teyvat.TeyvatBlocks.TELEPORT_COLUMN_CAPITAL_RED.getDefaultState());
            changed = true;
        }
        return changed;
    }
}
