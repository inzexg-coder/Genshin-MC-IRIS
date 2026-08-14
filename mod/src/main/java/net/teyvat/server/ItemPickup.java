package net.teyvat.server;

import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.TypeFilter;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.List;

/**
 * Ручной подбор лута на F (как в Genshin): игрок сам решает, что поднять.
 * Сервер берёт ближайший подбираемый предмет в радиусе и кладёт его в инвентарь
 * обычным путём (PlayerInventory.insertStack — тот же миксин, что шлёт
 * уведомление «ресурс получен» и не кладёт добычу в выбранный слот хотбара).
 */
public final class ItemPickup {
    /** Горизонтальный радиус подбора в блоках. */
    private static final double RANGE = 2.8;
    /** Вертикальное окно вокруг центра игрока (предметы на земле у ног). */
    private static final double DOWN = 1.5;
    private static final double UP = 0.6;

    private ItemPickup() {}

    /** F: подобрать ближайший предмет рядом с игроком. */
    public static void onPickupRequest(ServerPlayerEntity player) {
        if (!(player.getEntityWorld() instanceof ServerWorld world)) {
            return;
        }
        ItemEntity best = nearest(world, player);
        if (best == null) {
            return;
        }
        ItemStack stack = best.getStack();
        int count = stack.getCount();
        if (player.getInventory().insertStack(stack)) {
            world.playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.ENTITY_ITEM_PICKUP, SoundCategory.PLAYERS, 0.3f, 1.5f);
            player.sendPickup(best, count);
            player.triggerItemPickedUpByEntityCriteria(best);
            best.discard();
        }
    }

    private static ItemEntity nearest(ServerWorld world, PlayerEntity player) {
        Vec3d c = new Vec3d(player.getX(), player.getY(), player.getZ());
        Box box = new Box(c.x - RANGE, c.y - DOWN, c.z - RANGE,
                c.x + RANGE, c.y + UP, c.z + RANGE);
        List<ItemEntity> items = world.getEntitiesByType(
                TypeFilter.instanceOf(ItemEntity.class), box,
                e -> !e.isRemoved() && !e.cannotPickup() && !e.getStack().isEmpty());
        ItemEntity best = null;
        double bestDist = Double.MAX_VALUE;
        for (ItemEntity item : items) {
            double d = item.squaredDistanceTo(player);
            if (d < bestDist) {
                bestDist = d;
                best = item;
            }
        }
        return best;
    }
}
