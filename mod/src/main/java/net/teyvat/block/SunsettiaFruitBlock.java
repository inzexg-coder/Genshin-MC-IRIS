package net.teyvat.block;

import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.world.WorldView;

import net.teyvat.item.TeyvatItems;

/**
 * Плод Закатника, растёт в кроне дерева.
 *
 * <ul>
 *   <li>ПКМ — мгновенный сбор: частицы, звук, дроп 1-2 Закатника.</li>
 *   <li>ЛКМ — ванильная добыча с анимацией трещин (hardness 1.0f);
 *       при полном разрушении дроп идут через лут-таблицу,
 *       а {@link #afterBreak} планирует отращивание.</li>
 * </ul>
 */
public class SunsettiaFruitBlock extends Block {
    private static final VoxelShape SHAPE = VoxelShapes.cuboid(0.125, 0.125, 0.125, 0.875, 0.875, 0.875);

    public SunsettiaFruitBlock(AbstractBlock.Settings settings) {
        super(settings);
    }

    @Override
    protected VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return SHAPE;
    }

    /* ── ПКМ: мгновенный сбор ────────────────────────────────────── */

    @Override
    protected ActionResult onUse(BlockState state, World world, BlockPos pos,
                                 PlayerEntity player, BlockHitResult hit) {
        Hand hand = player.getActiveHand();
        player.swingHand(hand);
        if (world.isClient()) {
            spawnBreakParticles(world, pos);
        } else {
            collect(world, pos);
        }
        return ActionResult.SUCCESS;
    }

    /* ── ЛКМ: ванильная добыча с анимацией ──────────────────────── */

    /**
     * Вызывается сервером когда игрок начинает ломать блок (START_DESTROY).
     * Мы НЕ ломаем мгновенно — даём ванильной системе показать анимацию
     * трещин (hardness 1.0f ≈ 1.5 сек с рукой).
     */
    @Override
    protected void onBlockBreakStart(BlockState state, World world, BlockPos pos, PlayerEntity player) {
        if (world.isClient()) {
            spawnBreakParticles(world, pos);
        }
    }

    /**
     * После полного разрушения блока (ЛКМ) — планируем отращивание плода.
     * Дроп через лут-таблицу.
     */
    @Override
    public void afterBreak(World world, PlayerEntity player, BlockPos pos,
                           BlockState state, BlockEntity blockEntity, ItemStack tool) {
        super.afterBreak(world, player, pos, state, blockEntity, tool);
        if (!world.isClient()) {
            SunsettiaLeavesBlock.scheduleRegrow(world, pos);
        }
    }

    /* ── Общее ───────────────────────────────────────────────────── */

    /** Собрать плод (ПКМ): дроп 1-2 Закатника, удаление блока, отращивание. */
    public static void collect(World world, BlockPos pos) {
        int count = 1 + world.random.nextInt(2);
        dropStack(world, pos, new ItemStack(TeyvatItems.SUNSETTIA, count));
        world.playSound(null, pos, SoundEvents.BLOCK_CHERRY_SAPLING_BREAK,
                SoundCategory.BLOCKS, 1.0f, 1.0f);
        world.removeBlock(pos, false);
        SunsettiaLeavesBlock.scheduleRegrow(world, pos);
    }

    /** Частицы при разрушении плода на дереве (клиент). */
    public static void spawnBreakParticles(World world, BlockPos pos) {
        for (int i = 0; i < 10; i++) {
            world.addParticleClient(ParticleTypes.HAPPY_VILLAGER,
                    pos.getX() + 0.5 + (world.random.nextFloat() - 0.5) * 0.6,
                    pos.getY() + 0.5 + (world.random.nextFloat() - 0.5) * 0.6,
                    pos.getZ() + 0.5 + (world.random.nextFloat() - 0.5) * 0.6,
                    (world.random.nextFloat() - 0.5) * 0.15,
                    0.2,
                    (world.random.nextFloat() - 0.5) * 0.15);
        }
        world.playSound(null, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                SoundEvents.BLOCK_CHERRY_SAPLING_BREAK, SoundCategory.BLOCKS, 1.0f, 1.0f);
    }

    /** Плод висит в кроне, опору не проверяем (не сыпется). */
    @Override
    protected boolean canPlaceAt(BlockState state, WorldView world, BlockPos pos) {
        return true;
    }
}
