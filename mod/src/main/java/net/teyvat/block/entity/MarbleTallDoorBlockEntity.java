package net.teyvat.block.entity;

import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;

import net.teyvat.TeyvatBlockEntities;
import net.teyvat.block.MarbleTallDoorBlock;

/**
 * Хранит прогресс анимации двери на клиенте (0 = закрыта, 1 = открыта).
 * Серверная сторона ничего не тикает: состояние open приходит через blockstate.
 */
public class MarbleTallDoorBlockEntity extends BlockEntity {
    /** Скорость: полное открытие за 20 тиков (1 сек) — тяжёлая мраморная дверь. */
    private static final float ANIM_SPEED = 0.05f;

    private float progress;
    private float prevProgress;

    public MarbleTallDoorBlockEntity(BlockPos pos, BlockState state) {
        super(TeyvatBlockEntities.MARBLE_TALL_DOOR, pos, state);
    }

    public static void clientTick(World world, BlockPos pos, BlockState state, MarbleTallDoorBlockEntity be) {
        be.tickClient(state);
    }

    private void tickClient(BlockState state) {
        this.prevProgress = this.progress;
        boolean open = state.get(MarbleTallDoorBlock.OPEN);
        if (open && this.progress < 1.0f) {
            this.progress = Math.min(1.0f, this.progress + ANIM_SPEED);
        } else if (!open && this.progress > 0.0f) {
            this.progress = Math.max(0.0f, this.progress - ANIM_SPEED);
        }
    }

    public float getProgress(float tickDelta) {
        return MathHelper.lerp(tickDelta, this.prevProgress, this.progress);
    }
}
