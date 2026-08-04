package net.teyvat.block.entity;

import net.minecraft.block.BlockState;
import net.minecraft.block.enums.DoorHinge;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.block.BlockRenderManager;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactory;
import net.minecraft.client.render.block.entity.state.BlockEntityRenderState;
import net.minecraft.client.render.command.ModelCommandRenderer;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.model.BlockStateModel;
import net.minecraft.client.render.state.CameraRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;

import net.teyvat.block.MarbleTallDoorBlock;

/**
 * Рисует 3-блочную мраморную дверь целиком через BlockEntityRenderer.
 * Само полотно — обычные JSON-модели сегментов (marble_door_lower/middle/upper),
 * но блок помечен BlockRenderType.INVISIBLE, поэтому в чанке он не рендерится,
 * и BE-рендерер рисует каждое полотно с плавным поворотом вокруг оси блока
 * (как у ванильных дверей). Шейдер и labPBR-карты работают как с обычными блоками.
 */
public class MarbleTallDoorRenderer implements BlockEntityRenderer<MarbleTallDoorBlockEntity, MarbleTallDoorRenderState> {

    private final BlockRenderManager renderManager;

    public MarbleTallDoorRenderer(BlockEntityRendererFactory.Context ctx) {
        this.renderManager = ctx.renderManager();
    }

    @Override
    public MarbleTallDoorRenderState createRenderState() {
        return new MarbleTallDoorRenderState();
    }

    @Override
    public void updateRenderState(MarbleTallDoorBlockEntity blockEntity, MarbleTallDoorRenderState state,
            float tickProgress, Vec3d cameraPos, ModelCommandRenderer.CrumblingOverlayCommand crumblingOverlay) {
        BlockEntityRenderState.updateBlockEntityRenderState(blockEntity, state, crumblingOverlay);
        state.swingProgress = blockEntity.getProgress(tickProgress);
    }

    @Override
    public void render(MarbleTallDoorRenderState state, MatrixStack matrices,
            OrderedRenderCommandQueue queue, CameraRenderState cameraState) {
        BlockState blockState = state.blockState;
        if (!(blockState.getBlock() instanceof MarbleTallDoorBlock)) {
            return;
        }
        Direction facing = blockState.get(MarbleTallDoorBlock.FACING);
        boolean leftHinge = blockState.get(MarbleTallDoorBlock.HINGE) == DoorHinge.LEFT;
        float progress = MathHelper.clamp(state.swingProgress, 0.0f, 1.0f);
        // Поворот повторяет ванильную схему дверей (полотно на стороне facing.getOpposite()):
        // закрыто: полотно напротив facing; открыто: left hinge -> по часовой, right -> против.
        // Матрица вращается против часовой (JOML), поэтому знак обратный ванильному y из JSON.
        float base = (90.0f - facing.getHorizontalQuarterTurns() * 90.0f + 360.0f) % 360.0f;
        float yaw = base + (leftHinge ? -90.0f : 90.0f) * progress;

        BlockStateModel model = this.renderManager.getModel(blockState);
        matrices.push();
        matrices.translate(0.5, 0.5, 0.5);
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(yaw));
        matrices.translate(-0.5, -0.5, -0.5);
        queue.submitBlockStateModel(matrices, RenderLayer.getSolid(), model,
                1.0f, 1.0f, 1.0f, state.lightmapCoordinates, OverlayTexture.DEFAULT_UV, -1);
        matrices.pop();
    }
}
