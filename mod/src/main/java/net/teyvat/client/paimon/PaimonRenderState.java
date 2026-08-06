package net.teyvat.client.paimon;

import net.minecraft.client.render.entity.state.EntityRenderState;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

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
    /** Точки золотого шлейфа в локальных координатах (относительно trailBase). */
    public final List<Vec3d> trail = new ArrayList<>();
    /** База шлейфа — позиция ног сущности на момент обновления состояния рендера. */
    public Vec3d trailBase;
}
