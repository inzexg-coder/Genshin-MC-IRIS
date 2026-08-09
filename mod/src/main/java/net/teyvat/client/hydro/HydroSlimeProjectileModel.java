package net.teyvat.client.hydro;

import net.minecraft.client.model.ModelData;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.model.ModelPartBuilder;
import net.minecraft.client.model.ModelPartData;
import net.minecraft.client.model.ModelTransform;
import net.minecraft.client.model.TexturedModelData;
import net.minecraft.client.render.entity.model.EntityModel;

/** Водяная сфера Гидро слайма: плоский квадрат-билборд, всегда повёрнутый
 *  к камере (как частица). Сфера нарисована на текстуре 16×16. */
public class HydroSlimeProjectileModel extends EntityModel<HydroSlimeProjectileRenderState> {
    private static final int TEXTURE_WIDTH = 16;
    private static final int TEXTURE_HEIGHT = 16;

    public HydroSlimeProjectileModel(ModelPart root) {
        super(root);
    }

    public static TexturedModelData getTexturedModelData() {
        ModelData data = new ModelData();
        ModelPartData root = data.getRoot();
        // Плоский квадрат 16×16 (1 блок), тонкий — рендер вращает его к камере.
        root.addChild("orb",
                ModelPartBuilder.create().uv(0, 0).cuboid(-8.0f, -8.0f, -0.05f, 16.0f, 16.0f, 0.1f),
                ModelTransform.origin(0.0f, 0.0f, 0.0f));
        return TexturedModelData.of(data, TEXTURE_WIDTH, TEXTURE_HEIGHT);
    }

    @Override
    public void setAngles(HydroSlimeProjectileRenderState state) {
        // билборд — модель не поворачивается сама, рендер ориентирует её к камере
    }
}
