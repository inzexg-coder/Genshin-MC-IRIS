package net.teyvat.block;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.StructureWorldAccess;
import net.teyvat.TeyvatWood;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Рисует дерево Закатника с той же случайностью, что и обычный дуб:
 * высота ствола 4-6, крона-блоб с переменным радиусом (2-3), редкие
 * пропуски угловых листьев, 1-2 плода, свисающих со случайных сторон
 * кроны. Используется и генерацией мира, и ростом саженца.
 */
public final class SunsettiaTreePlacer {
    private SunsettiaTreePlacer() {}

    /**
     * Пытается вырастить дерево с корнем в base (низ ствола у земли).
     * Возвращает true, если хотя бы что-то поставили.
     */
    public static boolean grow(StructureWorldAccess world, BlockPos base, Random random) {
        if (world == null || base == null) return false;

        int trunkHeight = 4 + random.nextInt(3); // 4..6, как у дуба
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

        // Крона как у ванильного дуба: блоб с переменной шириной (2 или 3),
        // слои +1/-2 чуть уже, иногда крона редкая (пропущены углы).
        int mid = random.nextInt(2) == 0 ? 2 : 3;
        int[][] layers = {
                {1, Math.max(1, mid - 1)},
                {0, mid},
                {-1, mid},
                {-2, Math.max(1, mid - 1)}
        };
        for (int[] layer : layers) {
            int dy = layer[0];
            int r = layer[1];
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (dx * dx + dz * dz > r * r) continue;
                    // как у дуба: угловые листья иногда не ставятся
                    if (dx != 0 && dz != 0 && random.nextFloat() < 0.15f) continue;
                    BlockPos p = top.up(dy).add(dx, 0, dz);
                    if (world.isAir(p) || world.getBlockState(p).getBlock().getDefaultState().isReplaceable()) {
                        world.setBlockState(p, leaves, Block.NOTIFY_ALL);
                        changed = true;
                    }
                }
            }
        }

        // Плоды: 0-2 на дерево (мин 0, макс 2), свисают со случайных сторон
        // кроны; сорванные отрастают со временем через листву.
        int fruitCount = random.nextInt(3);
        List<Direction> dirs = new ArrayList<>(List.of(
                Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST));
        Collections.shuffle(dirs, new java.util.Random(random.nextLong()));
        int placed = 0;
        for (Direction dir : dirs) {
            if (placed >= fruitCount) break;
            // на шаг наружу и на ярус ниже самой широкой части кроны
            BlockPos p = top.up(-1).offset(dir, mid + 1);
            if (world.isAir(p)) {
                world.setBlockState(p, fruit, Block.NOTIFY_ALL);
                placed++;
                changed = true;
            }
        }

        return changed;
    }
}
