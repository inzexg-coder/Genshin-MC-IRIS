package net.teyvat.client.hydro;

import net.minecraft.client.render.entity.state.EntityRenderState;

/** Состояние рендера Гидро слайма (как у ванильного слайма). */
public class HydroSlimeRenderState extends EntityRenderState {
    public float yaw;
    /** Вертикальное растяжение: 0 = покой, ближе к 1 = прыжок вверх. */
    public float stretch;
    /** Масштаб модели: 1.4 ≈ 1.2 блока в ширину для куба 14×10×14. */
    public float scale = 1.4f;
}
