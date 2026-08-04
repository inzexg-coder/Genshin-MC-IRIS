package net.teyvat.block.entity;

import net.minecraft.block.BlockState;
import net.minecraft.client.render.block.entity.state.BlockEntityRenderState;

/**
 * Состояние рендера двери: прогресс анимации + состояние верхней половины
 * (обе створки рисуются из BlockEntity нижней половины, чтобы вращаться как одно полотно).
 */
public class MarbleDoorRenderState extends BlockEntityRenderState {
    public float swingProgress;
    public BlockState upperHalfState;
}
