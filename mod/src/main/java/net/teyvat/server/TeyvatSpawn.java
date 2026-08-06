package net.teyvat.server;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.minecraft.world.border.WorldBorder;
import net.teyvat.config.TeyvatConfig;
import net.teyvat.worldgen.TeyvatOceanEdge;

import java.util.Set;

/**
 * Спавн всех игроков в биоме «Пляж» (teyvat:teyvat_beach).
 * На старте сервера находит точку на пляже (по конфигу config/teyvat.json)
 * и делает её мировым спавном с правильным поворотом.
 * Новые игроки (без кровати и без отметки teyvat:welcomed) телепортируются туда один раз.
 */
public final class TeyvatSpawn {
    private static final Logger LOGGER = LoggerFactory.getLogger("Teyvat");
    private static final Identifier BEACH_BIOME_ID = Identifier.of("teyvat", "teyvat_beach");
    private static final RegistryKey<Biome> BEACH_BIOME = RegistryKey.of(RegistryKeys.BIOME, BEACH_BIOME_ID);
    private static final String WELCOME_TAG = "teyvat:welcomed";

    private static BlockPos beachSpawn;
    private static float beachYaw;

    private TeyvatSpawn() {
    }

    /** Вызывается на старте сервера: найти пляжный спавн и установить его мировым. */
    public static void prepare(MinecraftServer server) {
        beachSpawn = null;
        beachYaw = 0f;
        ServerWorld world = server.getOverworld();
        setupWorldBorder(world);
        beachSpawn(world);
    }

    /**
     * Мировой бордер = граница карты и барьер в море: квадрат с центром (0,0)
     * и стороной 4000 блоков. Северный край (z = -2000) упирается в глубокую
     * часть моря, поэтому дальше него уплыть нельзя.
     */
    private static void setupWorldBorder(ServerWorld world) {
        WorldBorder border = world.getWorldBorder();
        border.setCenter(0, 0);
        border.setSize(TeyvatOceanEdge.BORDER_SIZE);
    }

    /** Вызывается при входе игрока: телепортировать новичка на пляж один раз. */
    public static void welcome(ServerPlayerEntity player, MinecraftServer server) {
        if (player.getCommandTags().contains(WELCOME_TAG)) {
            return;
        }
        if (player.getRespawn() != null) {
            return; // у игрока есть своя точка возрождения (кровать и т.п.)
        }
        if (!TeyvatConfig.get().teleport_new_players) {
            return;
        }
        ServerWorld world = server.getOverworld();
        BlockPos pos = beachSpawn(world);
        float yaw = beachYaw;
        server.execute(() -> {
            int topY = world.getTopY(Heightmap.Type.MOTION_BLOCKING, pos.getX(), pos.getZ());
            double x = pos.getX() + 0.5;
            double y = topY + 1.0;
            double z = pos.getZ() + 0.5;
            player.teleport(world, x, y, z, Set.of(), yaw, 0f, false);
            player.addCommandTag(WELCOME_TAG);
        });
    }

    private static BlockPos beachSpawn(ServerWorld world) {
        if (beachSpawn != null) {
            return beachSpawn;
        }
        TeyvatConfig.Spawn cfg = TeyvatConfig.get().spawn;
        BlockPos pos;
        if (cfg.use_fixed_position) {
            int y = cfg.fixed_y;
            if (y <= 0) {
                y = world.getTopY(Heightmap.Type.MOTION_BLOCKING, cfg.fixed_x, cfg.fixed_z);
            }
            pos = new BlockPos(cfg.fixed_x, y, cfg.fixed_z);
        } else {
            BlockPos anchor = new BlockPos(cfg.anchor_x, 0, cfg.anchor_z);
            BlockPos found = findShoreSpawn(world, anchor);
            if (found == null) {
                found = findBeachSpawn(world, anchor, Math.max(16, cfg.search_radius));
            }
            if (found == null) {
                // вокруг якоря ничего не нашлось (например, чанки не загружены) —
                // ищем вокруг точки мирового спавна, она гарантированно загружена
                BlockPos worldSpawn = world.getSpawnPoint().globalPos().pos();
                found = findBeachSpawn(world, worldSpawn, Math.max(16, cfg.search_radius));
            }
            pos = found != null ? found : world.getSpawnPoint().globalPos().pos();
        }
        float yaw = cfg.yaw >= 0f ? cfg.yaw : autoYaw(world, pos);
        beachSpawn = pos;
        beachYaw = yaw;
        world.setSpawnPoint(WorldProperties.SpawnPoint.create(world.getRegistryKey(), pos, yaw, 0f));
        LOGGER.info("Пляжный спавн: x={}, y={}, z={}, yaw={} (море на севере, суша позади)", pos.getX(), pos.getY(), pos.getZ(), yaw);
        return pos;
    }

    /**
     * Поиск точки у кромки моря: полоса поперёк берега (z около уреза воды).
     * Море всегда на севере, поэтому полоса идёт вдоль X вокруг якоря.
     * Принимается и суша на уровне моря (песок по щиколотку в воде) —
     * это и есть самая кромка воды. Чанки полосы генерируются тут же.
     */
    private static BlockPos findShoreSpawn(ServerWorld world, BlockPos origin) {
        int zMin = TeyvatOceanEdge.WATERLINE_Z - 160;
        int zMax = TeyvatOceanEdge.WATERLINE_Z + 80;
        BlockPos best = null;
        int bestScore = Integer.MIN_VALUE;
        BlockPos bestAny = null;
        int bestAnyScore = Integer.MIN_VALUE;
        for (int x = origin.getX() - 256; x <= origin.getX() + 256; x += 8) {
            for (int z = zMin; z <= zMax; z += 8) {
                BlockPos cand = new BlockPos(x, 0, z);
                // Явная генерация чанка полосы (один раз при старте): getTopY и
                // getBiome без неё не создают чанки и возвращают заглушки.
                world.getChunk(cand.getX() >> 4, cand.getZ() >> 4);
                int topY = world.getTopY(Heightmap.Type.MOTION_BLOCKING, cand.getX(), cand.getZ());
                if (topY < world.getSeaLevel() - 1) {
                    continue;
                }
                if (!world.getBiome(cand).matchesKey(BEACH_BIOME)) {
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
                int dryScore = beachScore(world, top);
                if (dryScore > bestAnyScore) {
                    bestAnyScore = dryScore;
                    bestAny = top;
                }
                int water = seaAround(world, top);
                if (water <= 0) {
                    continue; // спавн только рядом с морем
                }
                int score = dryScore + water * 20;
                if (score > bestScore) {
                    bestScore = score;
                    best = top;
                }
            }
        }
        return best != null ? best : bestAny;
    }

    /** Сколько направлений вокруг точки занято морем (кольца до 24 блоков). */
    private static int seaAround(ServerWorld world, BlockPos top) {
        int count = 0;
        int[][] dirs = {{8, 0}, {-8, 0}, {0, 8}, {0, -8}, {6, 6}, {6, -6}, {-6, 6}, {-6, -6}};
        for (int d = 8; d <= 24; d += 8) {
            for (int[] dir : dirs) {
                int nx = top.getX() + dir[0] * d;
                int nz = top.getZ() + dir[1] * d;
                world.getChunk(nx >> 4, nz >> 4);
                int ny = world.getTopY(Heightmap.Type.MOTION_BLOCKING, nx, nz);
                if (ny <= world.getSeaLevel()) {
                    count++;
                    continue;
                }
                if (!world.getFluidState(new BlockPos(nx, ny, nz)).isEmpty()) {
                    count++;
                }
            }
        }
        return count;
    }

    /**
     * Поиск лучшей точки пляжа по спирали от origin:
     * биом пляжа, сухая земля выше уровня моря, над головой воздух.
     * Спавн обязан быть у самой кромки воды (озеро рядом), площадка — ровная.
     */
    private static BlockPos findBeachSpawn(ServerWorld world, BlockPos origin, int radius) {
        BlockPos best = null;
        int bestScore = Integer.MIN_VALUE;
        BlockPos bestAny = null;
        int bestAnyScore = Integer.MIN_VALUE;
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
                    int dryScore = beachScore(world, top);
                    if (dryScore > bestAnyScore) {
                        bestAnyScore = dryScore;
                        bestAny = top;
                    }
                    int water = waterAround(world, top);
                    if (water <= 0) {
                        continue; // спавн только рядом с водой
                    }
                    int score = dryScore + water * 30;
                    if (score > bestScore) {
                        bestScore = score;
                        best = top;
                    }
                }
            }
        }
        return best != null ? best : bestAny;
    }

    /** Оценка точки: песок под ногами + количество сухих твёрдых соседей в пределах 5x5. */
    private static int beachScore(ServerWorld world, BlockPos top) {
        int score = 0;
        int x = top.getX();
        int y = top.getY();
        int z = top.getZ();
        if (world.getBlockState(top.down()).isOf(Blocks.SAND)) {
            score += 10;
        }
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                int nx = x + dx;
                int nz = z + dz;
                if (!world.isChunkLoaded(nx >> 4, nz >> 4)) {
                    continue;
                }
                int ny = world.getTopY(Heightmap.Type.MOTION_BLOCKING, nx, nz);
                if (Math.abs(ny - y) > 2) {
                    continue;
                }
                BlockPos np = new BlockPos(nx, ny, nz);
                if (world.getFluidState(np).isEmpty()
                        && world.getFluidState(np.up()).isEmpty()
                        && world.getBlockState(np.down()).isFullCube(world, np.down())) {
                    score++;
                }
            }
        }
        return score;
    }

    /** Сколько направлений с водой вокруг точки (кольцо радиусом ~6). */
    private static int waterAround(ServerWorld world, BlockPos top) {
        int count = 0;
        int[] dxs = {6, -6, 0, 0, 4, 4, -4, -4};
        int[] dzs = {0, 0, 6, -6, 4, -4, 4, -4};
        for (int i = 0; i < dxs.length; i++) {
            int nx = top.getX() + dxs[i];
            int nz = top.getZ() + dzs[i];
            if (!world.isChunkLoaded(nx >> 4, nz >> 4)) {
                continue;
            }
            int ny = world.getTopY(Heightmap.Type.MOTION_BLOCKING, nx, nz);
            if (ny <= world.getSeaLevel()) {
                count++;
                continue;
            }
            if (!world.getFluidState(new BlockPos(nx, ny, nz)).isEmpty()) {
                count++;
            }
        }
        return count;
    }

    /** Автоповорот: сканируем по кругу, вода позади игрока, суша впереди. */
    private static float autoYaw(ServerWorld world, BlockPos pos) {
        float bestYaw = 0f;
        int bestWater = -1;
        for (int i = 0; i < 32; i++) {
            double rad = i * Math.PI * 2.0 / 32.0;
            double dx = Math.sin(rad);
            double dz = Math.cos(rad);
            int water = 0;
            for (int d = 6; d <= 24; d += 4) {
                int x = pos.getX() + (int) Math.round(dx * d);
                int z = pos.getZ() + (int) Math.round(dz * d);
                if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                    continue;
                }
                int topY = world.getTopY(Heightmap.Type.MOTION_BLOCKING, x, z);
                BlockPos p = new BlockPos(x, topY, z);
                if (topY <= world.getSeaLevel() || !world.getFluidState(p).isEmpty()) {
                    water++;
                }
            }
            if (water > bestWater) {
                bestWater = water;
                bestYaw = (float) Math.toDegrees(Math.atan2(dx, -dz));
            }
        }
        return bestYaw;
    }
}
