package net.teyvat.block.entity;

import net.minecraft.client.render.block.entity.state.BlockEntityRenderState;

/**
 * Состояние рендера сегмента двери: прогресс анимации копируется из BlockEntity
 * на клиенте, чтобы рендер не зависел от живых сущностей (новый пайплайн 1.21.10).
 */
public class MarbleTallDoorRenderState extends BlockEntityRenderState {
    public float swingProgress;
}
