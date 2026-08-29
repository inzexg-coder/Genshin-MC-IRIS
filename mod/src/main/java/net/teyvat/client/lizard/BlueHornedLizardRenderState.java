package net.teyvat.client.lizard;

import net.minecraft.client.render.entity.state.EntityRenderState;

/** Состояние рендера синей рогатой ящерицы. */
public class BlueHornedLizardRenderState extends EntityRenderState {
    /** Поворот тела (градусы). */
    public float yaw;
    /** Наклон головы (градусы). */
    public float pitch;
    /** Горизонтальный доворот головы относительно тела (градусы). */
    public float headYaw;
    /** Фаза шага (0..1) для маха ног и хвоста. */
    public float limbSwing;
    /** Амплитуда шага: 0 = стоит, >0 = идёт/бежит. */
    public float limbSwingAmount;
    /** Прогресс уползания под землю: 0 — на поверхности, 1 — скрылась. */
    public float burrowProgress;
}
