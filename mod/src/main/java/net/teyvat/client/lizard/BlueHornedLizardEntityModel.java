package net.teyvat.client.lizard;

import net.minecraft.client.model.ModelData;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.model.ModelPartBuilder;
import net.minecraft.client.model.ModelPartData;
import net.minecraft.client.model.ModelTransform;
import net.minecraft.client.model.TexturedModelData;
import net.minecraft.client.render.entity.model.EntityModel;
import net.minecraft.util.math.MathHelper;

/**
 * Модель синей рогатой ящерицы (порт геометрии из мод-пака Genshin Nature):
 * тело 3×2×7, голова с одним рогом, четыре ноги и хвост, текстура 64×64.
 * Ноги машут в такт ходьбе (как у мод-пака), хвост слегка виляет.
 */
public class BlueHornedLizardEntityModel extends EntityModel<BlueHornedLizardRenderState> {
    private final ModelPart head;
    private final ModelPart frontLeftLeg;
    private final ModelPart frontRightLeg;
    private final ModelPart backLeftLeg;
    private final ModelPart backRightLeg;
    private final ModelPart tail;

    public BlueHornedLizardEntityModel(ModelPart root) {
        super(root);
        this.head = root.getChild("head");
        this.frontLeftLeg = root.getChild("frontLeftLeg");
        this.frontRightLeg = root.getChild("frontRightLeg");
        this.backLeftLeg = root.getChild("backLeftLeg");
        this.backRightLeg = root.getChild("backRightLeg");
        this.tail = root.getChild("tail");
    }

    public static TexturedModelData getTexturedModelData() {
        ModelData data = new ModelData();
        ModelPartData root = data.getRoot();

        // Тело: 3×2×7, брюхо приподнято на 3/16 от земли.
        root.addChild("body",
                ModelPartBuilder.create().uv(0, 55).cuboid(-1.5f, 3.0f, -3.5f, 3.0f, 2.0f, 7.0f),
                ModelTransform.NONE);

        // Голова: 3×3×3 впереди тела, чуть выше линии спины.
        ModelPartData headPart = root.addChild("head",
                ModelPartBuilder.create().uv(0, 0).cuboid(-1.5f, -1.5f, -1.5f, 3.0f, 3.0f, 3.0f),
                ModelTransform.origin(0.0f, 5.0f, -4.5f));
        // Рог: 1×1×2 на макушке, чуть впереди центра головы.
        headPart.addChild("horn",
                ModelPartBuilder.create().uv(14, 0).cuboid(-0.5f, 1.5f, -1.0f, 1.0f, 1.0f, 2.0f),
                ModelTransform.NONE);

        // Четыре ноги по углам тела (pivot у бедра, куб свисает вниз до земли).
        ModelPartBuilder legBuilder = ModelPartBuilder.create().uv(60, 60).cuboid(-0.5f, -3.0f, -0.5f, 1.0f, 3.0f, 1.0f);
        root.addChild("frontLeftLeg", legBuilder, ModelTransform.origin(-1.0f, 3.0f, -2.5f));
        root.addChild("frontRightLeg", legBuilder, ModelTransform.origin(1.0f, 3.0f, -2.5f));
        root.addChild("backLeftLeg", legBuilder, ModelTransform.origin(-1.0f, 3.0f, 2.5f));
        root.addChild("backRightLeg", legBuilder, ModelTransform.origin(1.0f, 3.0f, 2.5f));

        // Хвост: 1×1×5 назад от тела, чуть задран вверх.
        root.addChild("tail",
                ModelPartBuilder.create().uv(52, 0).cuboid(-0.5f, -0.5f, 0.0f, 1.0f, 1.0f, 5.0f),
                ModelTransform.origin(0.0f, 3.5f, 3.5f));

        return TexturedModelData.of(data, 64, 64);
    }

    @Override
    public void setAngles(BlueHornedLizardRenderState state) {
        float limbSwing = state.limbSwing;
        float amount = state.limbSwingAmount;

        // Диагональная походка: левая передняя + правая задняя в фазе.
        this.frontLeftLeg.setAngles(-MathHelper.cos(limbSwing) * amount, 0.0f, 0.0f);
        this.frontRightLeg.setAngles(MathHelper.cos(limbSwing) * amount, 0.0f, 0.0f);
        this.backLeftLeg.setAngles(MathHelper.cos(limbSwing) * amount, 0.0f, 0.0f);
        this.backRightLeg.setAngles(-MathHelper.cos(limbSwing) * amount, 0.0f, 0.0f);

        // Хвост покачивается при беге — как настоящая ящерица.
        this.tail.setAngles(-0.3054f + MathHelper.sin(limbSwing * 0.5f) * 0.15f * amount, 0.0f, 0.0f);

        // Голова слегка поворачивается за взглядом.
        this.head.setAngles(
                (float) Math.toRadians(state.pitch),
                (float) Math.toRadians(state.headYaw),
                0.0f);
    }
}
