package net.teyvat.client.hydro;

import net.minecraft.client.model.ModelData;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.model.ModelPartBuilder;
import net.minecraft.client.model.ModelPartData;
import net.minecraft.client.model.ModelTransform;
import net.minecraft.client.model.TexturedModelData;
import net.minecraft.client.render.entity.model.EntityModel;

/**
 * Модель Гидро слайма — два куба из Hoyocraft: внешнее тело 14×10×14
 * и внутреннее «ядро» 10×8×10, оба на текстуре 64×64 (uv 0,0 и 0,24).
 * Низ внешнего куба стоит на земле, внутренний смещён вверх на 1 пиксель.
 */
public class HydroSlimeEntityModel extends EntityModel<HydroSlimeRenderState> {
    public HydroSlimeEntityModel(ModelPart root) {
        super(root);
    }

    public static TexturedModelData getTexturedModelData() {
        ModelData data = new ModelData();
        ModelPartData root = data.getRoot();

        // Внешнее тело: 14×10×14, низ у земли (как в geo-модели Hoyocraft).
        root.addChild("cube",
                ModelPartBuilder.create().uv(0, 0).cuboid(-7.0f, 0.0f, -7.0f, 14.0f, 10.0f, 14.0f),
                ModelTransform.NONE);

        // Внутреннее ядро: 10×8×10, приподнято на 1 пиксель.
        root.addChild("innerCube",
                ModelPartBuilder.create().uv(0, 24).cuboid(-5.0f, 1.0f, -5.0f, 10.0f, 8.0f, 10.0f),
                ModelTransform.NONE);

        return TexturedModelData.of(data, 64, 64);
    }
}
