package net.teyvat.block.entity;

import net.minecraft.block.BlockState;
import net.minecraft.block.enums.DoorHinge;
import net.minecraft.block.enums.DoubleBlockHalf;
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

import net.teyvat.block.MarbleDoorBlock;

/**
 * Рисует мраморную дверь целиком (обе половины из BlockEntity нижней) с плавным
 * поворотом вокруг вертикальной оси, проходящей через центр дверного проёма
 * (y = нижний блок + 1). Полотно — ванильные закрытые модели створок (open=false),
 * угол поворота зависит от progress анимации. labPBR-карты работают как обычно.
 */
public class MarbleDoorRenderer implements BlockEntityRenderer<MarbleDoorBlockEntity, MarbleDoorRenderState> {

    private final BlockRenderManager renderManager;

    public MarbleDoorRenderer(BlockEntityRendererFactory.Context ctx) {
        this.renderManager = ctx.renderManager();
    }

    @Override
    public MarbleDoorRenderState createRenderState() {
        return new MarbleDoorRenderState();
    }

    @Override
    public void updateRenderState(MarbleDoorBlockEntity blockEntity, MarbleDoorRenderState state,
            float tickProgress, Vec3d cameraPos, ModelCommandRenderer.CrumblingOverlayCommand crumblingOverlay) {
        BlockEntityRenderState.updateBlockEntityRenderState(blockEntity, state, crumblingOverlay);
        state.swingProgress = blockEntity.getProgress(tickProgress);
        state.upperHalfState = null;
        BlockState blockState = state.blockState;
        if (blockState.getBlock() instanceof MarbleDoorBlock
                && blockState.get(MarbleDoorBlock.HALF) == DoubleBlockHalf.LOWER
                && blockEntity.getWorld() != null) {
            BlockState upper = blockEntity.getWorld().getBlockState(blockEntity.getPos().up());
            if (upper.getBlock() instanceof MarbleDoorBlock
                    && upper.get(MarbleDoorBlock.HALF) == DoubleBlockHalf.UPPER) {
                state.upperHalfState = upper;
            }
        }
    }

    @Override
    public void render(MarbleDoorRenderState state, MatrixStack matrices,
            OrderedRenderCommandQueue queue, CameraRenderState cameraState) {
        BlockState blockState = state.blockState;
        if (!(blockState.getBlock() instanceof MarbleDoorBlock)) {
            return;
        }
        if (blockState.get(MarbleDoorBlock.HALF) != DoubleBlockHalf.LOWER) {
            return;
        }
        Direction facing = blockState.get(MarbleDoorBlock.FACING);
        boolean leftHinge = blockState.get(MarbleDoorBlock.HINGE) == DoorHinge.LEFT;
        float progress = MathHelper.clamp(state.swingProgress, 0.0f, 1.0f);
        // Тот же поворот, что у ванильных дверей: закрыто — полотно напротив facing,
        // открыто — left hinge по часовой, right — против (знак обратный из-за JOML).
        float base = (90.0f - facing.getHorizontalQuarterTurns() * 90.0f + 360.0f) % 360.0f;
        float yaw = base + (leftHinge ? -90.0f : 90.0f) * progress;

        renderHalf(blockState.with(MarbleDoorBlock.OPEN, false), matrices, queue, state, 0.5f, 1.0f, 0.5f, yaw);
        if (state.upperHalfState != null) {
            renderHalf(state.upperHalfState.with(MarbleDoorBlock.OPEN, false), matrices, queue, state,
                    0.5f, 0.0f, 0.5f, yaw);
        }
    }

    private void renderHalf(BlockState doorState, MatrixStack matrices, OrderedRenderCommandQueue queue,
            MarbleDoorRenderState state, float px, float py, float pz, float yaw) {
        BlockStateModel model = this.renderManager.getModel(doorState);
        matrices.push();
        matrices.translate(px, py, pz);
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(yaw));
        matrices.translate(-px, -py, -pz);
        queue.submitBlockStateModel(matrices, RenderLayer.getSolid(), model,
                1.0f, 1.0f, 1.0f, state.lightmapCoordinates, OverlayTexture.DEFAULT_UV, -1);
        matrices.pop();
    }
}
