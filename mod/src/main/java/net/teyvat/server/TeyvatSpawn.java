package net.teyvat.server;

import net.minecraft.block.BlockState;
import net.minecraft.network.packet.s2c.play.PositionFlag;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.WorldProperties;
import net.minecraft.world.biome.Biome;

import java.util.Set;

/**
 * Спавн всех игроков в биоме «Пляж» (teyvat:teyvat_beach).
 * На старте сервера ищет сухую точку в этом биоме и делает её мировым спавном.
 * Новые игроки (без кровати и без отметки teyvat:welcomed) телепортируются туда один раз.
 */
public final class TeyvatSpawn {
    private static final Identifier BEACH_BIOME_ID = Identifier.of("teyvat", "teyvat_beach");
    private static final RegistryKey<Biome> BEACH_BIOME = RegistryKey.of(RegistryKeys.BIOME, BEACH_BIOME_ID);
    private static final int SEARCH_RADIUS = 140; // внутри сгенерированной спавн-области
    private static final String WELCOME_TAG = "teyvat:welcomed";

    private static BlockPos beachSpawn;

    private TeyvatSpawn() {
    }

    /** Вызывается на старте сервера: найти пляжный спавн и установить его мировым. */
    public static void prepare(MinecraftServer server) {
        beachSpawn(server.getOverworld());
    }

    /** Вызывается при входе игрока: телепортировать новичка на пляж один раз. */
    public static void welcome(ServerPlayerEntity player, MinecraftServer server) {
        if (player.getCommandTags().contains(WELCOME_TAG)) {
            return;
        }
        if (player.getRespawn() != null) {
            return; // у игрока есть своя точка возрождения (кровать и т.п.)
        }
        ServerWorld world = server.getOverworld();
        BlockPos pos = beachSpawn(world);
        server.execute(() -> {
            int topY = world.getTopY(Heightmap.Type.MOTION_BLOCKING, pos.getX(), pos.getZ());
            double x = pos.getX() + 0.5;
            double y = topY + 1.0;
            double z = pos.getZ() + 0.5;
            player.teleport(world, x, y, z, Set.of(), 0f, 0f, false);
            player.addCommandTag(WELCOME_TAG);
        });
    }

    private static BlockPos beachSpawn(ServerWorld world) {
        if (beachSpawn != null) {
            return beachSpawn;
        }
        BlockPos origin = world.getSpawnPoint().globalPos().pos();
        BlockPos found = findBeachSpawn(world, origin, SEARCH_RADIUS);
        if (found != null) {
            beachSpawn = found;
            world.setSpawnPoint(WorldProperties.SpawnPoint.create(world.getRegistryKey(), found, 0f, 0f));
        } else {
            beachSpawn = origin;
        }
        return beachSpawn;
    }

    /** Поиск по спирали от origin: биом пляжа, сухая земля выше уровня моря, над головой воздух. */
    private static BlockPos findBeachSpawn(ServerWorld world, BlockPos origin, int radius) {
        for (int r = 0; r <= radius; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                        continue; // только кольцо текущего радиуса
                    }
                    BlockPos cand = origin.add(dx, 0, dz);
                    if (!world.isChunkLoaded(cand.getX() >> 4, cand.getZ() >> 4)) {
                        continue;
                    }
                    if (!world.getBiome(cand).matchesKey(BEACH_BIOME)) {
                        continue;
                    }
                    int topY = world.getTopY(Heightmap.Type.MOTION_BLOCKING, cand.getX(), cand.getZ());
                    if (topY <= world.getSeaLevel()) {
                        continue;
                    }
                    BlockPos top = new BlockPos(cand.getX(), topY, cand.getZ());
                    if (!world.getFluidState(top).isEmpty()) {
                        continue;
                    }
                    if (!world.getFluidState(top.up()).isEmpty()) {
                        continue;
                    }
                    BlockState below = world.getBlockState(top.down());
                    if (!below.isFullCube(world, top.down())) {
                        continue;
                    }
                    return top;
                }
            }
        }
        return null;
    }
}
