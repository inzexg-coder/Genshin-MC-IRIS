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

    /** Кладёт ступенчатый ромб-пьедестал точки телепортации в ТЕКУЩИЙ чанк.
     *   Нижняя подставка r=4, средняя ступень r=3, малый ромб r=2 — все на
     *   общем уровне centerY (+нижние). Всё сплошное (без земли). Вокруг
     *   расчищается воздух выше центрального уровня, чтобы точку было видно.
     *   Если withColumn — ставится плита и колонна над центральной ячейкой. */
    private boolean placeTeleportInChunk(FeatureContext<DefaultFeatureConfig> context, int x, int z, boolean withColumn) {
        var world = context.getWorld();
        ChunkPos chunkPos = new ChunkPos(context.getOrigin());
        int cMinX = chunkPos.getStartX();
        int cMinZ = chunkPos.getStartZ();
        int cMaxX = cMinX + 15;
        int cMaxZ = cMinZ + 15;
        boolean changed = false;

        // Единый уровень платформы: высота поверхности в центре (трайлхед) - 1.
        int centerY = world.getTopY(Heightmap.Type.WORLD_SURFACE, x, z) - 1;
        if (centerY < world.getBottomY() + 1) {
            for (int dx = -2; dx <= 2 && centerY < world.getBottomY() + 1; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    if (Math.abs(dx) + Math.abs(dz) > 2) continue;
                    int px = x + dx;
                    int pz = z + dz;
                    if (px < cMinX || px > cMaxX || pz < cMinZ || pz > cMaxZ) continue;
                    centerY = world.getTopY(Heightmap.Type.WORLD_SURFACE, px, pz) - 1;
                }
            }
        }

        // Двухуровневый ромб-пьедестал под точкой телепортации:
        //   - нижний ромб r=4 (подставка) на centerY-2 и centerY-1
        //   - средний ромб r=3 на centerY-1 (вьступающая ступень)
        //   - малый ромб r=2 на centerY — платформа, на которой стоит колонна
        // Всё сплошное (без земли), чтобы точка стояла на заметном ромбе.
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                if (Math.abs(dx) + Math.abs(dz) > 4) continue;
                int px = x + dx;
                int pz = z + dz;
                if (px < cMinX || px > cMaxX || pz < cMinZ || pz > cMaxZ) continue;
                for (int dy = 1; dy <= 9; dy++) {
                    setBlockState(world, new BlockPos(px, centerY + dy, pz), net.minecraft.block.Blocks.AIR.getDefaultState());
                }
                int ringDistance = Math.abs(dx) + Math.abs(dz);
                // Нижняя подставка r=4 (centerY-2).
                setBlockState(world, new BlockPos(px, centerY - 2, pz), net.teyvat.TeyvatBlocks.TELEPORT_PATH.getDefaultState());
                // Средняя ступень r=3 (centerY-1).
                if (ringDistance <= 3) {
                    setBlockState(world, new BlockPos(px, centerY - 1, pz), net.teyvat.TeyvatBlocks.TELEPORT_PATH.getDefaultState());
                }
                // Малый ромб r=2 (centerY) — платформа под колонной.
                if (ringDistance <= 2) {
                    setBlockState(world, new BlockPos(px, centerY, pz), net.teyvat.TeyvatBlocks.TELEPORT_PATH.getDefaultState());
                }
                changed = true;
            }
        }
        // Тонкое теснение r=1 (поверх платформы, тоже на общем уровне).
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
        // Расчистка территории ВОКРУГ платформы (радиус 7): полностью убираем все
        // блоки от уровня платформы и выше, чтобы НИЧЕГО не наезжало и не
        // прорастало сквозь точку. Платформа остаётся на общем уровне centerY,
        // вокруг неё — ровный пол из TELEPORT_PATH на том же уровне.
        for (int dx = -8; dx <= 8; dx++) {
            for (int dz = -8; dz <= 8; dz++) {
                if (Math.abs(dx) + Math.abs(dz) > 11) continue;
                int px = x + dx;
                int pz = z + dz;
                if (px < cMinX || px > cMaxX || pz < cMinZ || pz > cMaxZ) continue;
                boolean isPlatform = Math.abs(dx) <= 3 && Math.abs(dz) <= 3 && Math.abs(dx) + Math.abs(dz) <= 3;
                // Всё, что выше уровней платформы/пола, убираем в воздух.
                int floorY = centerY;
                int localTop = world.getTopY(Heightmap.Type.WORLD_SURFACE, px, pz);
                if (localTop >= floorY + 1) {
                    for (int dy = floorY + 1; dy <= Math.min(localTop, floorY + 9); dy++) {
                        setBlockState(world, new BlockPos(px, dy, pz), net.minecraft.block.Blocks.AIR.getDefaultState());
                    }
                }
                // Если здесь террейн ВЫШЕ платформы — срезаем его до уровня пола:
                // ставим пол из path-блока, чтобы не оставалось травы/земли вровень
                // с платформой и вокруг неё.
                if (localTop > floorY) {
                    setBlockState(world, new BlockPos(px, floorY, pz), net.teyvat.TeyvatBlocks.TELEPORT_PATH.getDefaultState());
                    changed = true;
                }
            }
        }

        // Центр: плита + колонна над уровнем платформы.
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
