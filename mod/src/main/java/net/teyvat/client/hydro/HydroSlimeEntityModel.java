package net.teyvat.client.hydro;

import net.minecraft.client.model.ModelData;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.model.ModelPartBuilder;
import net.minecraft.client.model.ModelPartData;
import net.minecraft.client.model.ModelTransform;
import net.minecraft.client.model.TexturedModelData;
import net.minecraft.client.render.entity.model.EntityModel;

/**
 * Модель Гидро слайма — точная копия ванильного слайма Minecraft
 * (внешний куб 8×8×8 + внутренний куб с глазами и ртом), но текстура
 * перекрашена в голубой «водный» цвет. Начало координат — у ног.
 */
public class HydroSlimeEntityModel extends EntityModel<HydroSlimeRenderState> {
    public HydroSlimeEntityModel(ModelPart root) {
        super(root);
    }

    public static TexturedModelData getTexturedModelData() {
        ModelData data = new ModelData();
        ModelPartData root = data.getRoot();

        // Внешнее тело — как у ванильного слайма (куб 8×8×8, низ у земли).
        root.addChild("cube",
                ModelPartBuilder.create().uv(0, 0).cuboid(-4.0f, 0.0f, -4.0f, 8.0f, 8.0f, 8.0f),
                ModelTransform.NONE);

        // Внутренний куб (светлое «брюшко» на текстуре) и черты лица.
        root.addChild("innerCube",
                ModelPartBuilder.create().uv(0, 16).cuboid(-3.0f, 1.0f, -3.0f, 6.0f, 6.0f, 6.0f),
                ModelTransform.NONE);
        root.addChild("right_eye",
                ModelPartBuilder.create().uv(32, 0).cuboid(-3.25f, 2.0f, -3.5f, 2.0f, 2.0f, 2.0f),
                ModelTransform.NONE);
        root.addChild("left_eye",
                ModelPartBuilder.create().uv(32, 4).cuboid(1.25f, 2.0f, -3.5f, 2.0f, 2.0f, 2.0f),
                ModelTransform.NONE);
        root.addChild("mouth",
                ModelPartBuilder.create().uv(32, 8).cuboid(0.0f, 5.0f, -3.5f, 1.0f, 1.0f, 1.0f),
                ModelTransform.NONE);

        return TexturedModelData.of(data, 64, 32);
    }
}
