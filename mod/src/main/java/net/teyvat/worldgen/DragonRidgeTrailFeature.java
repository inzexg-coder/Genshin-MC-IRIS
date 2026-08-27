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
public final class DragonRidgeTrailFeature extends Feature<DefaultFeatureConfig> {
    public static final Identifier ID =
            Identifier.of(TeyvatMod.MOD_ID, "dragon_ridge_trail");

    public DragonRidgeTrailFeature() {
        super(DefaultFeatureConfig.CODEC);
    }

    public static void register() {
        Registry.register(Registries.FEATURE, ID, new DragonRidgeTrailFeature());
    }

    @Override
    public boolean generate(FeatureContext<DefaultFeatureConfig> context) {
        ChunkPos chunkPos = new ChunkPos(context.getOrigin());
        if (!TeyvatDragonRidge.chunkMayContainTrail(chunkPos.getStartX(), chunkPos.getStartZ(),
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
                double fade = TeyvatDragonRidge.trailFadeFactor(x, z);
                if (fade <= -0.3) continue;

                int surfaceY = world.getTopY(Heightmap.Type.WORLD_SURFACE, x, z);
                BlockPos surface = new BlockPos(x, surfaceY, z);
                var block = world.getBlockState(surface).getBlock();
                if (block == Blocks.GRASS_BLOCK || block == Blocks.SAND
                        || block == Blocks.SHORT_GRASS || block == Blocks.TALL_GRASS) {
                    setBlockState(world, surface, Blocks.DIRT_PATH.getDefaultState());
                    changed = true;
                }
            }
        }

        // Точка телепортации: строим прямо при генерации содержащих её чанков.
        // Центр (TRAILHEAD) стоит на границе двух чанков по X, поэтому каждая
        // половина ромба-платформы кладётся своим чанком — никаких записей в
        // соседние чанки, никакого замедления генерации.
        if (chunkPos.x == TeyvatDragonRidge.TRAILHEAD_X >> 4
                && chunkPos.z == TeyvatDragonRidge.TRAILHEAD_Z >> 4) {
            changed |= placeTeleportInChunk(context, TeyvatDragonRidge.TRAILHEAD_X, TeyvatDragonRidge.TRAILHEAD_Z, true);
        } else if (chunkMayContainTeleportPlatform(chunkPos.getStartX(), chunkPos.getStartZ(),
                chunkPos.getEndX(), chunkPos.getEndZ())) {
            changed |= placeTeleportInChunk(context, TeyvatDragonRidge.TRAILHEAD_X, TeyvatDragonRidge.TRAILHEAD_Z, false);
        }

        return changed;
    }

    /** Содержит ли чанк хотя бы одну ячейку ромба-платформы точки телепортации. */
    private static boolean chunkMayContainTeleportPlatform(int minX, int minZ, int maxX, int maxZ) {
        int x = TeyvatDragonRidge.TRAILHEAD_X;
        int z = TeyvatDragonRidge.TRAILHEAD_Z;
        return x - 3 <= maxX && x + 3 >= minX && z - 3 <= maxZ && z + 3 >= minZ;
    }

    /** Кладёт ячейки ромба-платформы (|dx|+|dz| <= 3), попадающие в ТЕКУЩИЙ чанк:
     *  расчищает воздух над ячейкой и ставит блок платформы на локальную
     *  поверхность-1. Если withColumn — дополнительно ставит плиту и колонну
     *  над центральной ячейкой. Никаких записей за пределы чанка. */
    private boolean placeTeleportInChunk(FeatureContext<DefaultFeatureConfig> context, int x, int z, boolean withColumn) {
        var world = context.getWorld();
        ChunkPos chunkPos = new ChunkPos(context.getOrigin());
        int cMinX = chunkPos.getStartX();
        int cMinZ = chunkPos.getStartZ();
        int cMaxX = cMinX + 15;
        int cMaxZ = cMinZ + 15;
        boolean changed = false;

        // Сначала ромб-платформа r=3.
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                if (Math.abs(dx) + Math.abs(dz) > 3) continue;
                int px = x + dx;
                int pz = z + dz;
                if (px < cMinX || px > cMaxX || pz < cMinZ || pz > cMaxZ) continue;
                int py = world.getTopY(Heightmap.Type.WORLD_SURFACE, px, pz) - 1;
                for (int dy = 1; dy <= 5; dy++) {
                    setBlockState(world, new BlockPos(px, py + dy, pz), net.minecraft.block.Blocks.AIR.getDefaultState());
                }
                setBlockState(world, new BlockPos(px, py, pz), net.teyvat.TeyvatBlocks.TELEPORT_PATH.getDefaultState());
                changed = true;
            }
        }
        // Тонкое теснение r=1 (поверх центра платформы).
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (Math.abs(dx) + Math.abs(dz) > 1) continue;
                int px = x + dx;
                int pz = z + dz;
                if (px < cMinX || px > cMaxX || pz < cMinZ || pz > cMaxZ) continue;
                int py = world.getTopY(Heightmap.Type.WORLD_SURFACE, px, pz) - 1;
                setBlockState(world, new BlockPos(px, py, pz), net.teyvat.TeyvatBlocks.TELEPORT_PATH_THIN.getDefaultState());
                changed = true;
            }
        }

        // Центр: плита + колонна над уровнем платформы.
        if (withColumn && x >= cMinX && x <= cMaxX && z >= cMinZ && z <= cMaxZ) {
            int centerY = world.getTopY(Heightmap.Type.WORLD_SURFACE, x, z) - 1;
            setBlockState(world, new BlockPos(x, centerY + 1, z), net.teyvat.TeyvatBlocks.TELEPORT_SLAB_RED.getDefaultState());
            setBlockState(world, new BlockPos(x, centerY + 2, z), net.teyvat.TeyvatBlocks.TELEPORT_COLUMN_BASE_RED.getDefaultState());
            setBlockState(world, new BlockPos(x, centerY + 3, z), net.teyvat.TeyvatBlocks.TELEPORT_COLUMN_SHAFT_RED.getDefaultState());
            setBlockState(world, new BlockPos(x, centerY + 4, z), net.teyvat.TeyvatBlocks.TELEPORT_COLUMN_CAPITAL_RED.getDefaultState());
            changed = true;
        }
        return changed;
    }
}
