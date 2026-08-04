package net.teyvat.block;

import net.minecraft.block.Block;
import net.minecraft.block.BlockSetType;
import net.minecraft.block.BlockState;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.util.ActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.World;
import net.minecraft.world.event.GameEvent;

/**
 * Мраморная дверь Тейвата: обычная ванильная 2-блочная дверь (гарантированно
 * видимая модель, открывается/закрывается как oak_door), но медленная —
 * тяжёлое полотно меняет состояние с задержкой ~1 секунда через scheduleBlockTick.
 * Запрос на открытие хранится в свойстве PENDING_OPEN.
 */
public class MarbleDoorBlock extends DoorBlock {
    public static final BooleanProperty PENDING_OPEN = BooleanProperty.of("pending");
    /** Задержка открытия/закрытия, тики (24 = 1.2 сек). */
    public static final int DELAY_TICKS = 24;

    public MarbleDoorBlock(BlockSetType blockSetType, Settings settings) {
        super(blockSetType, settings);
        this.setDefaultState(this.getDefaultState().with(PENDING_OPEN, false));
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        super.appendProperties(builder);
        builder.add(PENDING_OPEN);
    }

    @Override
    protected ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, BlockHitResult hit) {
        if (state.get(POWERED)) {
            return ActionResult.PASS;
        }
        boolean idle = state.get(PENDING_OPEN) == state.get(OPEN);
        boolean target = idle ? !state.get(OPEN) : !state.get(PENDING_OPEN);
        this.setOpen(player, world, state, pos, target);
        return ActionResult.SUCCESS;
    }

    @Override
    public void setOpen(Entity entity, World world, BlockState state, BlockPos pos, boolean open) {
        if (world.isClient()) {
            return;
        }
        if (!state.isOf(this)) {
            return;
        }
        BlockPos lower = state.get(HALF) == DoubleBlockHalf.LOWER ? pos : pos.down();
        BlockState lowerState = world.getBlockState(lower);
        if (!lowerState.isOf(this)) {
            return;
        }
        // уже в нужном состоянии и без висящего запроса — не откладываем
        if (lowerState.get(OPEN) == open && lowerState.get(PENDING_OPEN) == open) {
            return;
        }
        world.setBlockState(lower, lowerState.with(PENDING_OPEN, open), 2);
        BlockPos upper = lower.up();
        BlockState upperState = world.getBlockState(upper);
        if (upperState.isOf(this)) {
            world.setBlockState(upper, upperState.with(PENDING_OPEN, open), 2);
        }
        world.scheduleBlockTick(lower, this, DELAY_TICKS);
    }

    @Override
    protected void scheduledTick(BlockState state, ServerWorld world, BlockPos pos, Random random) {
        if (!state.isOf(this)) {
            return;
        }
        BlockPos lower = state.get(HALF) == DoubleBlockHalf.LOWER ? pos : pos.down();
        BlockState lowerState = world.getBlockState(lower);
        if (!lowerState.isOf(this)) {
            return;
        }
        boolean open = lowerState.get(PENDING_OPEN);
        world.setBlockState(lower, lowerState.with(OPEN, open).with(PENDING_OPEN, false), 10);
        BlockPos upper = lower.up();
        BlockState upperState = world.getBlockState(upper);
        if (upperState.isOf(this)) {
            world.setBlockState(upper, upperState.with(OPEN, open).with(PENDING_OPEN, false), 10);
        }
        world.playSound(null, lower, open ? this.getBlockSetType().doorOpen() : this.getBlockSetType().doorClose(), SoundCategory.BLOCKS, 1.0F, world.getRandom().nextFloat() * 0.1F + 0.9F);
        world.emitGameEvent(null, open ? GameEvent.BLOCK_OPEN : GameEvent.BLOCK_CLOSE, lower);
    }
}
