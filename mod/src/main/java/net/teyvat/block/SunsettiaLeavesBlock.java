package net.teyvat.block;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.World;
import net.teyvat.TeyvatWood;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Листва Закатника. Отвечает за отрастание плодов: на дереве живёт
 * 0-2 плода; когда плод собрали (или дерево родилось без плодов),
 * листва со временем отращивает их снова (до 2 шт).
 */
public class SunsettiaLeavesBlock extends Block {
    /** Задержка отрастания после сбора плода: 1-3 минуты. */
    private static final int REGROW_MIN_TICKS = 1200;
    private static final int REGROW_MAX_TICKS = 3600;

    public SunsettiaLeavesBlock(Settings settings) {
        super(settings);
    }

    @Override
    protected void randomTick(BlockState state, ServerWorld world, BlockPos pos, Random random) {
        // Дерево, родившееся без плодов, постепенно обрастает: с небольшим
        // шансом планируем отращивание (scheduledTick проверит количество).
        if (random.nextFloat() < 0.3f) {
            scheduleRegrow(world, pos);
        }
    }

    @Override
    protected void scheduledTick(BlockState state, ServerWorld world, BlockPos pos, Random random) {
        tryRegrow(world, pos, random);
    }

    /** Запланировать попытку отращивания плодов рядом с позицией. */
    public static void scheduleRegrow(World world, BlockPos near) {
        if (world == null || world.isClient() || near == null) return;
        BlockPos leaf = findTreeLeaf(world, near);
        if (leaf == null) return; // дерева рядом нет (срублено) — не растём
        int delay = REGROW_MIN_TICKS + world.random.nextInt(REGROW_MAX_TICKS - REGROW_MIN_TICKS + 1);
        world.scheduleBlockTick(leaf, TeyvatWood.SUNSETTIA_LEAVES, delay);
    }

    /** Попытка отрастить плод на дереве рядом с листом-тикером. */
    private static void tryRegrow(ServerWorld world, BlockPos from, Random random) {
        BlockPos top = findTreeTop(world, from);
        if (top == null) return; // ствол срубили — плоды не растут
        if (countFruits(world, top) >= 2) return; // уже полное дерево
        if (placeFruit(world, top, random)) return;
        // подходящего места не нашлось — попробуем чуть позже
        world.scheduleBlockTick(from, TeyvatWood.SUNSETTIA_LEAVES, 200 + random.nextInt(400));
    }

    /** Сколько плодов висит на дереве с верхушкой ствола в top. */
    private static int countFruits(World world, BlockPos top) {
        int count = 0;
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                for (int dy = -3; dy <= 2; dy++) {
                    if (world.getBlockState(top.add(dx, dy, dz)).isOf(TeyvatWood.SUNSETTIA_FRUIT)) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    /** Повесить плод в свободное место возле кроны; true, если повесили. */
    private static boolean placeFruit(ServerWorld world, BlockPos top, Random random) {
        List<Direction> dirs = new ArrayList<>(List.of(
                Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST));
        Collections.shuffle(dirs, new java.util.Random(random.nextLong()));
        for (Direction dir : dirs) {
            for (int dist = 2; dist <= 4; dist++) {
                for (int dy = -2; dy <= 0; dy++) {
                    BlockPos p = top.up(dy).offset(dir, dist);
                    if (!world.isAir(p)) continue;
                    if (!attachedToLeaves(world, p)) continue;
                    world.setBlockState(p, TeyvatWood.SUNSETTIA_FRUIT.getDefaultState(), Block.NOTIFY_ALL);
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean attachedToLeaves(World world, BlockPos p) {
        for (Direction d : Direction.Type.HORIZONTAL) {
            if (world.getBlockState(p.offset(d)).isOf(TeyvatWood.SUNSETTIA_LEAVES)) return true;
        }
        return world.getBlockState(p.up()).isOf(TeyvatWood.SUNSETTIA_LEAVES)
                || world.getBlockState(p.down()).isOf(TeyvatWood.SUNSETTIA_LEAVES);
    }

    /** Лист дерева рядом с позицией (3x3, до 6 блоков вверх/вниз) или null. */
    private static BlockPos findTreeLeaf(World world, BlockPos near) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = -6; dy <= 6; dy++) {
                    BlockPos p = near.add(dx, dy, dz);
                    if (world.getBlockState(p).isOf(TeyvatWood.SUNSETTIA_LEAVES)) {
                        return p;
                    }
                }
            }
        }
        return null;
    }

    /** Верхушка ствола (верхний блок бревна) дерева рядом с позицией или null. */
    private static BlockPos findTreeTop(World world, BlockPos from) {
        int best = Integer.MIN_VALUE;
        BlockPos top = null;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int down = 0; down <= 9; down++) {
                    BlockPos p = from.add(dx, -down, dz);
                    if (!world.getBlockState(p).isOf(TeyvatWood.SUNSETTIA_LOG)) continue;
                    // поднимаемся по стволу до верхнего бревна
                    while (world.getBlockState(p.up()).isOf(TeyvatWood.SUNSETTIA_LOG)) {
                        p = p.up();
                    }
                    if (p.getY() > best) {
                        best = p.getY();
                        top = p;
                    }
                    break;
                }
            }
        }
        return top;
    }
}
