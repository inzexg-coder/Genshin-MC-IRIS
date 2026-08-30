package net.teyvat.block;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.ActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.world.WorldView;

import net.teyvat.item.TeyvatItems;

/**
 * Плод Закатника, растёт в кроне дерева. Клик по плоду собирает его:
 * выпадает 1-2 Закатника, блок исчезает. Как сбор ягод в Genshin.
 */
public class SunsettiaFruitBlock extends Block {
    private static final VoxelShape SHAPE = VoxelShapes.cuboid(0.25, 0.0, 0.25, 0.75, 0.75, 0.75);

    public SunsettiaFruitBlock(Settings settings) {
        super(settings);
    }

    @Override
    protected VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return SHAPE;
    }

    /** Клик собирает плод: дроп 1-2 Закатника и удаление блока. */
    @Override
    protected ActionResult onUse(BlockState state, World world, BlockPos pos,
                                 PlayerEntity player, BlockHitResult hit) {
        if (world.isClient()) {
            return ActionResult.SUCCESS;
        }
        int count = 1 + world.random.nextInt(2);
        dropStack(world, pos, new ItemStack(TeyvatItems.SUNSETTIA, count));
        world.playSound(null, pos, SoundEvents.BLOCK_CHERRY_SAPLING_BREAK,
                SoundCategory.BLOCKS, 1.0f, 1.0f);
        world.removeBlock(pos, false);
        return ActionResult.SUCCESS;
    }

    /** Плод висит в кроне, опору не проверяем (не сыпется). */
    @Override
    protected boolean canPlaceAt(BlockState state, WorldView world, BlockPos pos) {
        return true;
    }
}
