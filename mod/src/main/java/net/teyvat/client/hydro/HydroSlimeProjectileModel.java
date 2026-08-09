package net.teyvat.client.hydro;

import net.minecraft.client.model.Dilation;
import net.minecraft.client.model.ModelData;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.model.ModelPartBuilder;
import net.minecraft.client.model.ModelPartData;
import net.minecraft.client.model.ModelTransform;
import net.minecraft.client.model.TexturedModelData;
import net.minecraft.client.render.entity.model.EntityModel;

/** Водяная сфера Гидро слайма: плотный шар с бликом, текстура 32×32. */
public class HydroSlimeProjectileModel extends EntityModel<HydroSlimeProjectileRenderState> {
    private static final int TEXTURE_WIDTH = 32;
    private static final int TEXTURE_HEIGHT = 32;

    public HydroSlimeProjectileModel(ModelPart root) {
        super(root);
    }

    public static TexturedModelData getTexturedModelData() {
        ModelData data = new ModelData();
        ModelPartData root = data.getRoot();
        // Шар: куб с лёгким расширением, каждая грань несёт градиент сферы.
        root.addChild("orb",
                ModelPartBuilder.create().uv(0, 0).cuboid(-1.7f, -1.7f, -1.7f, 3.4f, 3.4f, 3.4f, new Dilation(0.04f)),
                ModelTransform.origin(0.0f, 0.0f, 0.0f));
        // Объёмный блик на верхней передней грани.
        root.addChild("highlight",
                ModelPartBuilder.create().uv(0, 0).cuboid(-0.55f, -2.35f, -1.85f, 1.1f, 0.9f, 0.45f),
                ModelTransform.origin(0.0f, 0.0f, 0.0f));
        return TexturedModelData.of(data, TEXTURE_WIDTH, TEXTURE_HEIGHT);
    }

    @Override
    public void setAngles(HydroSlimeProjectileRenderState state) {
        // сфера вращается вместе с направлением полёта — достаточно yaw/pitch рендера
    }
}
