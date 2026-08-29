package net.teyvat.item;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.teyvat.entity.BlueHornedLizardEntity;

/** Яйцо призыва синей рогатой ящерицы: правый клик спавнит ящерицу
 *  туда, куда смотрит игрок. Предмет для творческого режима и тестов. */
public class BlueHornedLizardSpawnEggItem extends Item {
    private static final double RAYCAST_DISTANCE = 4.5;

    public BlueHornedLizardSpawnEggItem(Settings settings) {
        super(settings);
    }

    @Override
    public ActionResult use(World world, PlayerEntity user, Hand hand) {
        if (world.isClient()) {
            return ActionResult.SUCCESS;
        }
        BlockPos pos = spawnPos(user);
        BlueHornedLizardEntity lizard = new BlueHornedLizardEntity(BlueHornedLizardEntity.TYPE, world);
        lizard.setPosition(pos.getX() + 0.5, pos.getY() + 0.05, pos.getZ() + 0.5);
        world.spawnEntity(lizard);
        if (!user.getAbilities().creativeMode) {
            user.getStackInHand(hand).decrement(1);
        }
        return ActionResult.SUCCESS;
    }

    /** Блок, куда смотрит игрок; если луч не попал в блок — перед ним. */
    private static BlockPos spawnPos(PlayerEntity user) {
        HitResult hit = user.raycast(RAYCAST_DISTANCE, 0.0F, false);
        if (hit.getType() == HitResult.Type.BLOCK && hit instanceof BlockHitResult blockHit) {
            return blockHit.getBlockPos().offset(blockHit.getSide());
        }
        Vec3d eye = user.getEyePos();
        Vec3d look = user.getRotationVec(0.0F);
        return BlockPos.ofFloored(eye.x + look.x * 2.0, eye.y + look.y * 2.0, eye.z + look.z * 2.0);
    }
}
