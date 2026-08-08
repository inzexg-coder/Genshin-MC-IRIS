package net.teyvat.client.hydro;

import net.minecraft.client.render.entity.state.EntityRenderState;

/** Состояние рендера Гидро слайма: поворот и squash-and-stretch. */
public class HydroSlimeRenderState extends EntityRenderState {
    public float yaw;
    /** Вертикальное растяжение (1 = покой, >1 — прыжок вверх, <1 — падение). */
    public float stretch = 1.0f;
    /** Горизонтальное сжатие (обратно stretch). */
    public float squash = 1.0f;
    /** Лёгкое покачивание на месте. */
    public float bob;
}
