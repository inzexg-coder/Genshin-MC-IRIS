package net.teyvat.block.entity;

import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;

import net.teyvat.TeyvatBlockEntities;
import net.teyvat.block.MarbleDoorBlock;

/**
 * Прогресс анимации двери (0 = закрыта, 1 = открыта). Тикает только на клиенте:
 * состояние open приходит через обычный blockstate (ванильная синхронизация).
 * Полное открытие ~1.4 сек — медленнее обычных дверей (мгновенных).
 */
public class MarbleDoorBlockEntity extends BlockEntity {
    /** Скорость: полное открытие за ~70 тиков (3.5 сек это много, берём 1.4 сек => 0.014). */
    private static final float ANIM_SPEED = 0.014f;

    private float progress;
    private float prevProgress;

    public MarbleDoorBlockEntity(BlockPos pos, BlockState state) {
        super(TeyvatBlockEntities.MARBLE_DOOR, pos, state);
    }

    public static void clientTick(World world, BlockPos pos, BlockState state, MarbleDoorBlockEntity be) {
        be.tickClient(state);
    }

    private void tickClient(BlockState state) {
        this.prevProgress = this.progress;
        boolean open = state.get(MarbleDoorBlock.OPEN);
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
