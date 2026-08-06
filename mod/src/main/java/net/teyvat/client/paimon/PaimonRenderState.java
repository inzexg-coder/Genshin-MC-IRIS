package net.teyvat.client.paimon;

import net.minecraft.client.render.entity.state.EntityRenderState;

/** Состояние рендера Паймон: угол поворота, наклон и фаза полёта. */
public class PaimonRenderState extends EntityRenderState {
    /** Горизонтальный угол (yaw) сущности. */
    public float yaw;
    /** Вертикальный угол (pitch) сущности. */
    public float pitch;
    /** true — летит за игроком, false — знакомится в начале. */
    public boolean following;
    /** Покачивание при полёте. */
    public float bob;
}
