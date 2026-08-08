package net.teyvat.client.hydro;

import net.minecraft.client.render.entity.state.EntityRenderState;

/** Состояние рендера Гидро слайма (как у ванильного слайма). */
public class HydroSlimeRenderState extends EntityRenderState {
    public float yaw;
    /** Вертикальное растяжение: 0 = покой, ближе к 1 = прыжок вверх. */
    public float stretch;
    /** Размер (как size у ванильного слайма): 2 = куб ровно в один блок. */
    public int size = 2;
}
