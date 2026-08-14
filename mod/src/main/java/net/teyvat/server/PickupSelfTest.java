package net.teyvat.server;

import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Автотест автоподбора прямо в игре: /teyvat selftest бросает камень у ног
 * игрока и через 3 секунды проверяет, лежит ли он на месте. Если предмет
 * исчез и камень появился в инвентаре — автоподбор НЕ отключён (игра
 * запущена со старым jar). Нужно стоять на месте и не нажимать F.
 */
public final class PickupSelfTest {
    private static final int CHECK_TICKS = 60;
    private static final Map<UUID, Run> RUNS = new HashMap<>();

    private record Run(ItemEntity item, int stonesBefore, int ticksLeft) {}

    private PickupSelfTest() {}

    public static void start(ServerPlayerEntity player) {
        if (!(player.getEntityWorld() instanceof ServerWorld world)) {
            return;
        }
        RUNS.remove(player.getUuid());
        ItemEntity item = new ItemEntity(world, player.getX(), player.getY() + 0.4, player.getZ(),
                new ItemStack(Items.STONE, 1));
        item.setPickupDelay(0);
        item.setVelocity(Vec3d.ZERO);
        world.spawnEntity(item);
        RUNS.put(player.getUuid(), new Run(item, countStones(player), CHECK_TICKS));
        player.sendMessage(Text.literal(
                "§e[Teyvat] §fАвтотест: камень брошен у ног. §eСтой на месте 3 секунды и не нажимай F§f."), false);
    }

    /** Каждый серверный тик: обратный отсчёт и финальная проверка. */
    public static void tick(MinecraftServer server) {
        if (RUNS.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<UUID, Run>> it = RUNS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Run> entry = it.next();
            Run run = entry.getValue();
            if (run.ticksLeft() > 1) {
                entry.setValue(new Run(run.item(), run.stonesBefore(), run.ticksLeft() - 1));
                continue;
            }
            it.remove();
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(entry.getKey());
            if (player == null) {
                return;
            }
            if (!run.item().isRemoved()) {
                player.sendMessage(Text.literal(
                        "§a[Teyvat] ✓ Автоподбор отключён: камень остался лежать. Подними его на F."), false);
            } else {
                int now = countStones(player);
                if (now > run.stonesBefore()) {
                    player.sendMessage(Text.literal(
                            "§c[Teyvat] ✗ БАГ: камень поднялся сам — автоподбор активен! "
                                    + "Проверь версию мода (v0.9.109) и обнови jar."), false);
                } else {
                    player.sendMessage(Text.literal(
                            "§e[Teyvat] ? Камень исчез, но в инвентарь не попал. Повтори тест ещё раз."), false);
                }
            }
        }
    }

    private static int countStones(ServerPlayerEntity player) {
        int n = 0;
        var inv = player.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (stack.isOf(Items.STONE)) {
                n += stack.getCount();
            }
        }
        return n;
    }
}
