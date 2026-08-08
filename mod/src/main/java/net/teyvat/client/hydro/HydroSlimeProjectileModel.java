package net.teyvat.client.hydro;

import net.minecraft.client.model.Dilation;
import net.minecraft.client.model.ModelData;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.model.ModelPartBuilder;
import net.minecraft.client.model.ModelPartData;
import net.minecraft.client.model.ModelTransform;
import net.minecraft.client.model.TexturedModelData;
import net.minecraft.client.render.entity.model.EntityModel;

/** Маленькая водяная капля — модель снаряда Гидро слайма. */
public class HydroSlimeProjectileModel extends EntityModel<HydroSlimeProjectileRenderState> {
    private static final int TEXTURE_WIDTH = 16;
    private static final int TEXTURE_HEIGHT = 16;

    public HydroSlimeProjectileModel(ModelPart root) {
        super(root);
    }

    public static TexturedModelData getTexturedModelData() {
        ModelData data = new ModelData();
        ModelPartData root = data.getRoot();
        ModelPartBuilder builder = ModelPartBuilder.create();
        builder.uv(0, 0).cuboid(-1.8f, -2.0f, -1.8f, 3.6f, 3.0f, 3.6f, new Dilation(0.02f));
        builder.uv(0, 0).cuboid(-1.2f, -3.6f, -1.2f, 2.4f, 1.8f, 2.4f, new Dilation(0.02f));
        root.addChild("orb", builder, ModelTransform.origin(0.0f, 0.0f, 0.0f));
        // Блик на капле.
        ModelPartBuilder hl = ModelPartBuilder.create();
        hl.uv(12, 0).cuboid(-0.5f, -3.0f, -1.9f, 1.0f, 1.0f, 0.4f);
        root.addChild("highlight", hl, ModelTransform.origin(0.0f, 0.0f, 0.0f));
        return TexturedModelData.of(data, TEXTURE_WIDTH, TEXTURE_HEIGHT);
    }

    @Override
    public void setAngles(HydroSlimeProjectileRenderState state) {
        // капля вращается вместе с направлением полёта — достаточно yaw/pitch рендера
    }
}
