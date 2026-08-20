package net.teyvat.server;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.teyvat.TeyvatBlocks;
import net.teyvat.network.TeleportStatePayload;
import net.teyvat.server.WikiDiscoveries;
import net.teyvat.wiki.TeyvatWiki;
import net.teyvat.progression.ProgressionStore;
import net.teyvat.network.ExpGainPayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Серверная часть активации точек телепортации.
 * Состояние хранится в тегах игрока (teyvat:tpact_X_Y_Z) — как квесты и вики,
 * переживает перезаход и работает и в одиночке, и на выделенном сервере.
 * Для каждого игрока своя визуальная картина: активация одного игрока не меняет
 * вид для другого.
 */
public final class TeleportActivationManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("Teyvat");
    private static final String TAG_PREFIX = "teyvat:tpact_";
    /** Максимальное расстояние от игрока до плиты для активации (блоки). */
    private static final double ACTIVATE_RANGE = 6.0;

    private TeleportActivationManager() {}

    /**
     * Попытка активировать точку телепортации.
     * @return true если активация прошла успешно (новая точка).
     */
    public static boolean tryActivate(ServerPlayerEntity player, BlockPos slabPos) {
        if (isActivated(player, slabPos)) {
            return false;
        }
        if (!player.getBlockPos().isWithinDistance(slabPos, ACTIVATE_RANGE)) {
            return false;
        }
        var state = player.getEntityWorld().getBlockState(slabPos);
        if (!state.isOf(TeyvatBlocks.TELEPORT_SLAB_RED) && !state.isOf(TeyvatBlocks.TELEPORT_SLAB_BLUE)) {
            return false;
        }
        // Активируем
        player.addCommandTag(tag(slabPos));
        LOGGER.info("Игрок {} активировал точку телепортации на {}", player.getName().getString(), slabPos);
        // Вики: открываем запись о точке телепортации
        WikiDiscoveries.discover(player, TeyvatWiki.ID_TELEPORT);
        // Опыт приключений за активацию
        ProgressionStore.addArExp(player, 50);
        ServerPlayNetworking.send(player, new ExpGainPayload(50, false));
        // Отправляем обновлённое состояние клиенту
        ServerPlayNetworking.send(player, new TeleportStatePayload(new ArrayList<>(getActivatedPositions(player))));
        // Эффекты частиц для активирующего игрока
        spawnActivationParticles(player, slabPos);
        return true;
    }

    public static boolean isActivated(ServerPlayerEntity player, BlockPos slabPos) {
        return player.getCommandTags().contains(tag(slabPos));
    }

    /** Все активированные точки игрока. */
    public static Set<BlockPos> getActivatedPositions(ServerPlayerEntity player) {
        Set<BlockPos> positions = new HashSet<>();
        for (String tag : player.getCommandTags()) {
            if (tag.startsWith(TAG_PREFIX)) {
                try {
                    String coords = tag.substring(TAG_PREFIX.length());
                    String[] parts = coords.split("_");
                    if (parts.length == 3) {
                        positions.add(new BlockPos(
                                Integer.parseInt(parts[0]),
                                Integer.parseInt(parts[1]),
                                Integer.parseInt(parts[2])
                        ));
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return positions;
    }

    /** Отправить полное состояние активации игроку (вызывается при входе). */
    public static void syncToClient(ServerPlayerEntity player) {
        Set<BlockPos> positions = getActivatedPositions(player);
        if (!positions.isEmpty()) {
            ServerPlayNetworking.send(player, new TeleportStatePayload(new ArrayList<>(positions)));
        }
    }

    /** Эффект частиц при активации: взрыв enchant + end_rod вокруг колонны. */
    private static void spawnActivationParticles(ServerPlayerEntity player, BlockPos slabPos) {
        ServerWorld world = (ServerWorld) player.getEntityWorld();
        for (int dy = 0; dy <= 3; dy++) {
            BlockPos colPos = slabPos.up(dy);
            double x = colPos.getX() + 0.5;
            double y = colPos.getY() + 0.5;
            double z = colPos.getZ() + 0.5;
            // Синие искры enchant — основной взрыв
            world.spawnParticles(ParticleTypes.ENCHANT, x, y, z, 20, 0.6, 1.0, 0.6, 0.15);
            // Белые искры end_rod — свечение
            world.spawnParticles(ParticleTypes.END_ROD, x, y, z, 10, 0.4, 0.7, 0.4, 0.03);
        }
        // Дополнительный взряд у основания — «волна» активации
        world.spawnParticles(ParticleTypes.ELECTRIC_SPARK,
                slabPos.getX() + 0.5, slabPos.getY() + 0.5, slabPos.getZ() + 0.5,
                30, 1.5, 0.5, 1.5, 0.2);
    }

    private static String tag(BlockPos pos) {
        return TAG_PREFIX + pos.getX() + "_" + pos.getY() + "_" + pos.getZ();
    }
}
