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
import net.teyvat.config.TeyvatConfig;

/**
 * Защита от ломания блоков: глобально (по флагу config/teyvat.json → world.no_block_breaking)
 * и на пляже (teyvat:teyvat_beach) с переходной полосой (teyvat:teyvat_beach_edge),
 * где нельзя ломать и ставить блоки. Никаких уведомлений — события просто
 * отменяют действие, как будто ничего не было.
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
        // Левая кнопка по блоку (начало ломания) — отменяем и не шлём пакет:
        // глобальный запрет ломания или защита пляжа.
        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) ->
                breakingForbidden(world, pos) ? ActionResult.FAIL : ActionResult.PASS);

        // Правая кнопка по блоку (установка блока, открытие и т.п.) — отменяем.
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) ->
                isProtected(world, hitResult.getBlockPos()) ? ActionResult.FAIL : ActionResult.PASS);

        // Серверная страховка от полного ломания.
        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) ->
                !breakingForbidden(world, pos));
    }

    /** Ломать нельзя: глобальный запрет из конфига или защищённая зона пляжа. */
    public static boolean breakingForbidden(World world, BlockPos pos) {
        if (TeyvatConfig.get().world.no_block_breaking) {
            return true;
        }
        return isProtected(world, pos);
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
