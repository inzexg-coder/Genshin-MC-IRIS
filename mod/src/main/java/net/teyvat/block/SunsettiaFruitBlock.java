package net.teyvat.block;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
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
 * Плод Закатника, растёт в кроне дерева. Собирается кликом (ПКМ) или
 * ломается ударом меча (ЛКМ): выпадает 1-2 Закатника, блок исчезает.
 */
public class SunsettiaFruitBlock extends Block {
    // Хитбокс почти во весь блок, чтобы в плод легко попасть и ПКМ и ЛКМ
    // (сама моделька маленькая — текстура меньше листвы).
    private static final VoxelShape SHAPE = VoxelShapes.cuboid(0.125, 0.125, 0.125, 0.875, 0.875, 0.875);

    public SunsettiaFruitBlock(Settings settings) {
        super(settings);
    }

    @Override
    protected VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return SHAPE;
    }

    /** Клик (ПКМ) срывает плод: анимация руки, частицы, дроп 1-2 Закатника. */
    @Override
    protected ActionResult onUse(BlockState state, World world, BlockPos pos,
                                 PlayerEntity player, BlockHitResult hit) {
        Hand hand = player.getActiveHand();
        player.swingHand(hand);
        if (world.isClient()) {
            spawnPickParticles(world, pos);
        } else {
            collect(world, pos);
        }
        return ActionResult.SUCCESS;
    }

    /** Удар мечом (ЛКМ) тоже срывает плод — дроп 1-2 Закатника. */
    @Override
    protected void onBlockBreakStart(BlockState state, World world, BlockPos pos, PlayerEntity player) {
        if (world.isClient()) {
            spawnPickParticles(world, pos);
        } else {
            collect(world, pos);
        }
    }

    /** Небольшая россыпь частиц при срыве плода (клиент). */
    public static void spawnPickParticles(World world, BlockPos pos) {
        for (int i = 0; i < 6; i++) {
            world.addParticleClient(ParticleTypes.HAPPY_VILLAGER,
                    pos.getX() + 0.5 + (world.random.nextFloat() - 0.5) * 0.4,
                    pos.getY() + 0.5 + (world.random.nextFloat() - 0.5) * 0.4,
                    pos.getZ() + 0.5 + (world.random.nextFloat() - 0.5) * 0.4,
                    (world.random.nextFloat() - 0.5) * 0.1,
                    0.15,
                    (world.random.nextFloat() - 0.5) * 0.1);
        }
    }

    /** Собрать плод (удар или клик): дроп 1-2 Закатника, удаление блока, отращивание. */
    public static void collect(World world, BlockPos pos) {
        int count = 1 + world.random.nextInt(2);
        dropStack(world, pos, new ItemStack(TeyvatItems.SUNSETTIA, count));
        world.playSound(null, pos, SoundEvents.BLOCK_CHERRY_SAPLING_BREAK,
                SoundCategory.BLOCKS, 1.0f, 1.0f);
        world.removeBlock(pos, false);
        // плод скоро отрастёт снова (1-3 минуты)
        SunsettiaLeavesBlock.scheduleRegrow(world, pos);
    }

    /** Плод висит в кроне, опору не проверяем (не сыпется). */
    @Override
    protected boolean canPlaceAt(BlockState state, WorldView world, BlockPos pos) {
        return true;
    }
}
