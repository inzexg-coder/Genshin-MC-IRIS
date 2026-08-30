package net.teyvat.block;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.StructureWorldAccess;
import net.teyvat.TeyvatWood;

/**
 * Рисует дерево Закатника в стиле обычного дуба: ствол 4-6,
 * округлая крона в 4 слоя, плоды свисают с краёв кроны.
 * Используется и генерацией мира, и ростом саженца.
 */
public final class SunsettiaTreePlacer {
    private SunsettiaTreePlacer() {}

    /**
     * Пытается вырастить дерево с корнем в base (низ ствола у земли).
     * Возвращает true, если хотя бы что-то поставили.
     */
    public static boolean grow(StructureWorldAccess world, BlockPos base, Random random) {
        if (world == null || base == null) return false;

        int trunkHeight = 4 + random.nextInt(3); // 4..6
        BlockPos root = base;
        boolean changed = false;
        BlockState log = TeyvatWood.SUNSETTIA_LOG.getDefaultState();
        BlockState leaves = TeyvatWood.SUNSETTIA_LEAVES.getDefaultState();
        BlockState fruit = TeyvatWood.SUNSETTIA_FRUIT.getDefaultState();

        // Ствол: первый блок у земли (может заменить траву), дальше вверх.
        for (int i = 0; i < trunkHeight + 1; i++) {
            BlockPos p = root.up(i);
            if (i == 0 || world.isAir(p) || world.getBlockState(p).getBlock().getDefaultState().isReplaceable()) {
                world.setBlockState(p, log, Block.NOTIFY_ALL);
                changed = true;
            } else {
                trunkHeight = i;
                break;
            }
        }
        if (trunkHeight < 3) return changed;

        BlockPos top = root.up(trunkHeight - 1);

        // Крона как у дуба: слой +1 — макушка (r=1), 0 и -1 — ширина (r=2),
        // -2 — нижняя юбка (r=1). Округлость за счёт отсечения углов.
        int[][] layers = {{1, 1}, {0, 2}, {-1, 2}, {-2, 1}};
        for (int[] layer : layers) {
            int dy = layer[0];
            int r = layer[1];
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (dx * dx + dz * dz > r * r) continue;
                    BlockPos p = top.up(dy).add(dx, 0, dz);
                    if (world.isAir(p) || world.getBlockState(p).getBlock().getDefaultState().isReplaceable()) {
                        world.setBlockState(p, leaves, Block.NOTIFY_ALL);
                        changed = true;
                    }
                }
            }
        }

        // Плоды: свисают с краёв кроны по сторонам света (2-4 шт).
        for (Direction dir : Direction.Type.HORIZONTAL) {
            if (random.nextFloat() < 0.75f) {
                BlockPos p = top.up(-2).add(dir.getOffsetX() * 2, 0, dir.getOffsetZ() * 2);
                if (world.isAir(p)) {
                    world.setBlockState(p, fruit, Block.NOTIFY_ALL);
                    changed = true;
                }
            }
        }

        // Ещё один плод под самой макушкой, свисает вглубь кроны — редко.
        if (random.nextFloat() < 0.3f) {
            BlockPos p = top.up(-2);
            if (world.isAir(p)) {
                world.setBlockState(p, fruit, Block.NOTIFY_ALL);
                changed = true;
            }
        }

        return changed;
    }
}
