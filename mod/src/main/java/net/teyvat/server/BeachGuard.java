package net.teyvat.server;

import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;

/**
 * Защита пляжа: на пляже (teyvat:teyvat_beach) и в переходной полосе к полям
 * (teyvat:teyvat_beach_edge) нельзя ломать и ставить блоки. Никаких
 * уведомлений — события просто отменяют действие, как будто ничего не было.
 */
public final class BeachGuard {
    private static final RegistryKey<Biome> BEACH_BIOME =
            RegistryKey.of(RegistryKeys.BIOME, Identifier.of("teyvat", "teyvat_beach"));
    private static final RegistryKey<Biome> EDGE_BIOME =
            RegistryKey.of(RegistryKeys.BIOME, Identifier.of("teyvat", "teyvat_beach_edge"));

    private BeachGuard() {
    }

    /** Зарегистрировать события защиты пляжа (клиент и сервер). */
    public static void register() {
        // Левая кнопка по блоку (начало ломания) — отменяем и не шлём пакет.
        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) ->
                isProtected(world, pos) ? ActionResult.FAIL : ActionResult.PASS);

        // Правая кнопка по блоку (установка блока, открытие и т.п.) — отменяем.
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) ->
                isProtected(world, hitResult.getBlockPos()) ? ActionResult.FAIL : ActionResult.PASS);

        // Серверная страховка от полного ломания.
        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) ->
                !isProtected(world, pos));
    }

    /** Пляж и переходная полоса защищены от ломания и установки блоков. */
    public static boolean isProtected(World world, BlockPos pos) {
        if (world == null || pos == null || !world.isInBuildLimit(pos)) {
            return false;
        }
        return world.getBiome(pos).matchesKey(BEACH_BIOME)
                || world.getBiome(pos).matchesKey(EDGE_BIOME);
    }
}
