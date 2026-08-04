package net.teyvat.block;

import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockSetType;
import net.minecraft.block.BlockState;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityTicker;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import net.teyvat.block.entity.MarbleDoorBlockEntity;

/**
 * Мраморная дверь: ванильная 2-блочная дверь (полотно/повороты/звуки как oak_door),
 * но открывается МЕДЛЕННО — плавная анимация створок через BlockEntity-рендерер.
 * Полотно в чанке не рендерится (INVISIBLE), анимацию рисует MarbleDoorRenderer.
 */
public class MarbleDoorBlock extends DoorBlock implements BlockEntityProvider {

    public MarbleDoorBlock(BlockSetType blockSetType, Settings settings) {
        super(blockSetType, settings);
    }

    @Override
    protected BlockRenderType getRenderType(BlockState state) {
        return BlockRenderType.INVISIBLE;
    }

    @Override
    public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return new MarbleDoorBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(World world, BlockState state, BlockEntityType<T> type) {
        return world.isClient() ? (BlockEntityTicker<T>) (BlockEntityTicker<MarbleDoorBlockEntity>) MarbleDoorBlockEntity::clientTick : null;
    }
}
