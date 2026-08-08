package net.teyvat.client.hydro;

import net.minecraft.client.model.Dilation;
import net.minecraft.client.model.ModelData;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.model.ModelPartBuilder;
import net.minecraft.client.model.ModelPartData;
import net.minecraft.client.model.ModelTransform;
import net.minecraft.client.model.TexturedModelData;
import net.minecraft.client.render.entity.model.EntityModel;

/**
 * Воксельная модель Гидро слайма по образцу Genshin: каплевидное
 * голубое тело, светлое «брюшко», маленькие глазки и рот, тёмная
 * капля-корона на макушке с бликом. Начало координат — у ног.
 */
public class HydroSlimeEntityModel extends EntityModel<HydroSlimeRenderState> {
    private static final int TEXTURE_WIDTH = 64;
    private static final int TEXTURE_HEIGHT = 64;

    private final ModelPart crown;
    private final ModelPart crownHighlight;
    private final ModelPart belly;

    public HydroSlimeEntityModel(ModelPart root) {
        super(root);
        this.crown = root.getChild("crown");
        this.crownHighlight = root.getChild("crownHighlight");
        this.belly = root.getChild("belly");
    }

    public static TexturedModelData getTexturedModelData() {
        ModelData data = new ModelData();
        ModelPartData root = data.getRoot();

        // Тело: три сужающихся слоя + купол — каплевидный силуэт.
        ModelPartBuilder bodyBuilder = ModelPartBuilder.create();
        bodyBuilder.uv(0, 0).cuboid(-6.0f, 0.0f, -6.0f, 12.0f, 4.0f, 12.0f, new Dilation(0.02f));
        bodyBuilder.uv(0, 0).cuboid(-5.0f, 4.0f, -5.0f, 10.0f, 4.0f, 10.0f, new Dilation(0.02f));
        bodyBuilder.uv(0, 0).cuboid(-4.0f, 8.0f, -4.0f, 8.0f, 3.0f, 8.0f, new Dilation(0.02f));
        bodyBuilder.uv(0, 0).cuboid(-3.0f, 11.0f, -3.0f, 6.0f, 2.0f, 6.0f, new Dilation(0.02f));
        root.addChild("body", bodyBuilder, ModelTransform.origin(0.0f, 0.0f, 0.0f));

        // Светлое «брюшко» спереди, чуть выступает из тела.
        ModelPartBuilder bellyBuilder = ModelPartBuilder.create();
        bellyBuilder.uv(32, 0).cuboid(-2.5f, 4.0f, -6.7f, 5.0f, 4.0f, 1.2f, new Dilation(0.02f));
        root.addChild("belly", bellyBuilder, ModelTransform.origin(0.0f, 0.0f, 0.0f));

        // Глазки и рот — маленькие тёмные кубики на передней грани.
        ModelPartBuilder faceBuilder = ModelPartBuilder.create();
        faceBuilder.uv(0, 32).cuboid(-2.5f, 7.2f, -6.6f, 1.3f, 1.6f, 0.7f);
        faceBuilder.uv(0, 32).cuboid(1.2f, 7.2f, -6.6f, 1.3f, 1.6f, 0.7f);
        faceBuilder.uv(16, 32).cuboid(-0.9f, 5.4f, -6.6f, 1.8f, 0.8f, 0.6f);
        root.addChild("face", faceBuilder, ModelTransform.origin(0.0f, 0.0f, 0.0f));

        // Капля-корона: тёмно-голубая капля + блик.
        ModelPartBuilder crownBuilder = ModelPartBuilder.create();
        crownBuilder.uv(32, 16).cuboid(-2.0f, 13.0f, -2.0f, 4.0f, 3.0f, 4.0f);
        crownBuilder.uv(32, 16).cuboid(-1.2f, 15.8f, -1.2f, 2.4f, 1.0f, 2.4f);
        root.addChild("crown", crownBuilder, ModelTransform.origin(0.0f, 0.0f, 0.0f));

        ModelPartBuilder highlightBuilder = ModelPartBuilder.create();
        highlightBuilder.uv(48, 16).cuboid(-0.6f, 14.0f, -2.3f, 1.2f, 1.2f, 0.6f);
        root.addChild("crownHighlight", highlightBuilder, ModelTransform.origin(0.0f, 0.0f, 0.0f));

        return TexturedModelData.of(data, TEXTURE_WIDTH, TEXTURE_HEIGHT);
    }

    @Override
    public void setAngles(HydroSlimeRenderState state) {
        float phase = (float) Math.sin(state.age * 0.22f);
        // Капля слегка покачивается, брюшко «дышит».
        this.crown.setAngles(0.0f, phase * 0.08f, phase * 0.06f);
        this.crownHighlight.setAngles(0.0f, phase * 0.08f, phase * 0.06f);
        this.belly.setAngles(0.0f, 0.0f, -phase * 0.02f);
    }
}
