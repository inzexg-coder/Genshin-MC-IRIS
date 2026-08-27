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
import net.minecraft.server.world.ChunkTicketType;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.border.WorldBorder;
import net.teyvat.TeyvatBlocks;
import net.teyvat.config.TeyvatConfig;
import net.teyvat.worldgen.TeyvatDragonRidge;
import net.teyvat.worldgen.TeyvatOceanEdge;
import net.teyvat.worldgen.TeyvatXEdge;

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
    private static final Identifier GRASS_BIOME_ID = Identifier.of("teyvat", "teyvat_plains");
    private static final RegistryKey<Biome> GRASS_BIOME = RegistryKey.of(RegistryKeys.BIOME, GRASS_BIOME_ID);
    private static final Identifier TRAIL_BIOME_ID = Identifier.of("teyvat", "dragon_ridge_path");
    private static final RegistryKey<Biome> TRAIL_BIOME = RegistryKey.of(RegistryKeys.BIOME, TRAIL_BIOME_ID);
    private static final Identifier EDGE_BIOME_ID = Identifier.of("teyvat", "teyvat_beach_edge");
    private static final RegistryKey<Biome> EDGE_BIOME = RegistryKey.of(RegistryKeys.BIOME, EDGE_BIOME_ID);
    private static final String WELCOME_TAG = "teyvat:welcomed";

    private static BlockPos beachSpawn;
    private static float beachYaw;
    private static boolean teleportBuilt = false;

    private TeyvatSpawn() {
    }

    /** Вызывается на старте сервера: установить бордер и мировой спавн ДО подключения игроков. */
    public static void prepare(MinecraftServer server) {
        beachSpawn = null;
        beachYaw = 0f;
        ServerWorld world = server.getOverworld();
        setupWorldBorder(world);

        TeyvatConfig.Spawn cfg = TeyvatConfig.get().spawn;
        int sx = cfg.use_fixed_position ? cfg.fixed_x : 0;
        int sz = cfg.use_fixed_position
                ? (cfg.fixed_z != 0 ? cfg.fixed_z : cfg.anchor_z != 0 ? cfg.anchor_z : TeyvatOceanEdge.BEACH_CENTER_Z)
                : (cfg.anchor_z != 0 ? cfg.anchor_z : TeyvatOceanEdge.BEACH_CENTER_Z);

        // Загружаем чанк для высоты.
        world.getChunk(sx >> 4, sz >> 4);
        int sy = world.getTopY(Heightmap.Type.MOTION_BLOCKING, sx, sz);

        BlockPos spawnPos = new BlockPos(sx, sy, sz);
        world.setSpawnPoint(WorldProperties.SpawnPoint.create(
                world.getRegistryKey(), spawnPos, cfg.yaw >= 0f ? cfg.yaw : 0f, 0f));

        beachSpawn = spawnPos;
        beachYaw = cfg.yaw >= 0f ? cfg.yaw : 0f;

        // Широкий радиус загрузки чанков: Iris/Sodium требуют больше чанков
        // для закрытия экрана "Загрузка территории".
        ChunkPos spawnChunk = new ChunkPos(sx >> 4, sz >> 4);
        int radius = 5; // 11x11 = 121 чанков (~1936 блоков)
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                world.getChunkManager().addTicket(
                        ChunkTicketType.PLAYER_LOADING,
                        new ChunkPos(spawnChunk.x + dx, spawnChunk.z + dz),
                        radius);
            }
        }
        LOGGER.info("Мировой спавн: ({}, {}, {}), загружено {}x{} чанков",
                sx, sy, sz, radius * 2 + 1, radius * 2 + 1);
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

    /** Серверный тик: безопасно строим точку телепортации у начала тропы,
     *  когда чанк TRAILHEAD уже загружен (без принудительной генерации,
     *  которая вызывает бесконечную "Загрузку территории"). */
    public static void serverTickMaybeBuildTeleport(MinecraftServer server) {
        if (teleportBuilt) return;
        ServerWorld world = server.getOverworld();
        int chunkX = TeyvatDragonRidge.TRAILHEAD_X >> 4;
        int chunkZ = TeyvatDragonRidge.TRAILHEAD_Z >> 4;
        if (!world.isChunkLoaded(chunkX, chunkZ)) {
            return; // ждём, пока чанк сам загрузится
        }
        teleportBuilt = true;
        try {
            buildTeleportNearTrailhead(world);
        } catch (Exception e) {
            LOGGER.warn("Не удалось построить точку телепортации", e);
        }
    }

    /** Находит ровную площадку возле TRAILHEAD и строит точку телепортации.
     *  Чанк уже загружен (проверено в serverTickMaybeBuildTeleport), поэтому
     *  getTopY/setBlockState безопасны и не форсируют генерацию. */
    private static void buildTeleportNearTrailhead(ServerWorld world) {
        int centerX = TeyvatDragonRidge.TRAILHEAD_X;
        int centerZ = TeyvatDragonRidge.TRAILHEAD_Z;

        // Ищем ровную площадку по обе стороны тропы (по X), шаг 1 блок
        int[] xs = new int[41];
        for (int i = 0; i < 41; i++) xs[i] = centerX - 20 + i;
        for (int x : xs) {
            int topY = world.getTopY(Heightmap.Type.MOTION_BLOCKING, x, centerZ);
            if (topY < world.getSeaLevel()) continue;
            if (!world.getFluidState(new BlockPos(x, topY, centerZ)).isEmpty()) continue;

            // Проверяем ровность 5x5 (макс перепад 1 блок)
            int baseY = topY;
            boolean flat = true;
            for (int sdx = -2; sdx <= 2 && flat; sdx++) {
                for (int sdz = -2; sdz <= 2 && flat; sdz++) {
                    int ny = world.getTopY(Heightmap.Type.MOTION_BLOCKING, x + sdx, centerZ + sdz);
                    if (Math.abs(ny - baseY) > 1) flat = false;
                }
            }
            if (!flat) continue;

            // Ставим точку в самом начале, ближе к пляжу (южнее), не на самой тропе
            BlockPos spot = new BlockPos(x, topY, centerZ);
            if (x == centerX && topY == baseY) {
                // Ставим чуть в сторону от середины
                spot = new BlockPos(x + 3, topY, centerZ);
            }
            LOGGER.info("Точка телепортации (тик): x={}, z={}, y={}", spot.getX(), spot.getZ(), spot.getY());
            buildTeleportPoint(world, spot);
            LOGGER.info(">>> ТЕЛЕПОРТ ПОСТРОЕН (тик) <<<");
            return;
        }

        // Фолбэк: просто offset 20 от TRAILHEAD
        int fx = centerX + 20;
        int fz = centerZ;
        int fy = world.getTopY(Heightmap.Type.MOTION_BLOCKING, fx, fz);
        if (fy < world.getSeaLevel()) fy = world.getSeaLevel() + 1;
        BlockPos fallback = new BlockPos(fx, fy, fz);
        LOGGER.info("Точка телепортации (фолбэк-тик): x={}, z={}, y={}", fx, fz, fy);
        buildTeleportPoint(world, fallback);
        LOGGER.info(">>> ТЕЛЕПОРТ ПОСТРОЕН (фолбэк-тик) <<<");
    }

    /** Вызывается при входе игрока: пометить как приветствованного.
     *  Телепорт не нужен — мировой спавн уже установлен в prepare(). */
    public static void welcome(ServerPlayerEntity player, MinecraftServer server) {
        if (player.getCommandTags().contains(WELCOME_TAG)) {
            return;
        }
        player.addCommandTag(WELCOME_TAG);
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
            // Ленивый спавн: используем фиксированную точку на пляже (центр полукруга).
            // findShoreSpawn/findBeachSpawn форсируют генерацию тысяч чанков
            // и вызывают бесконечную "Загрузку территории".
            int spawnX = cfg.anchor_x != 0 ? cfg.anchor_x : 0;
            int spawnZ = cfg.anchor_z != 0 ? cfg.anchor_z : TeyvatOceanEdge.BEACH_CENTER_Z;
            int y = world.getTopY(Heightmap.Type.MOTION_BLOCKING, spawnX, spawnZ);
            pos = new BlockPos(spawnX, y, spawnZ);
        }

        // Спавн-точка = на пляже у моря
        float yaw = cfg.yaw >= 0f ? cfg.yaw : autoYaw(world, pos);
        beachSpawn = pos;
        beachYaw = yaw;
        world.setSpawnPoint(WorldProperties.SpawnPoint.create(world.getRegistryKey(), pos, yaw, 0f));
        LOGGER.info("Спавн-точка (пляж): x={}, y={}, z={}, yaw={}", pos.getX(), pos.getY(), pos.getZ(), yaw);

        // Точка телепортации = на границе с равнинами (строим ОДИН раз)
        BlockPos teleportPos = findTrailheadSpawn(world, pos);
        if (teleportPos == null) {
            teleportPos = findGrassBorderSpawn(world, pos);
        }
        if (teleportPos != null) {
            if (!teleportExistsNear(world, teleportPos, 12)) {
                buildTeleportPoint(world, teleportPos);
                LOGGER.info("Построена точка телепортации: x={}, y={}, z={}", teleportPos.getX(), teleportPos.getY(), teleportPos.getZ());
                // Отправляем сообщение всем игрокам
                LOGGER.info(">>> ТЕЛЕПОРТ ПОСТРОЕН <<<");
            } else {
                LOGGER.info("Точка телепортации уже существует поблизости");
                LOGGER.info(">>> ТЕЛЕПОРТ УЖЕ СУЩЕСТВУЕТ <<<");
            }
        } else {
            LOGGER.warn("Не удалось найти начало Драконьего хребта для точки телепортации!");
        }

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
        // Сканируем от центра пляжа к краям: спавн должен быть в середине полосы,
        // между боковыми биомами, а не на стыке с холмами.
        int half = TeyvatXEdge.BEACH_HALF;
        for (int dx = 0; dx <= half; dx += 8) {
            int[] xs = dx == 0 ? new int[]{origin.getX()} : new int[]{origin.getX() + dx, origin.getX() - dx};
            for (int x : xs) {
                for (int z = zMin; z <= zMax; z += 8) {
                    BlockPos cand = new BlockPos(x, 0, z);
                    // Явная генерация чанка полосы (один раз при старте): getTopY и
                    // getBiome без неё не создают чанки и возвращают заглушки.
                    world.getChunk(cand.getX() >> 4, cand.getZ() >> 4);
                    int topY = world.getTopY(Heightmap.Type.MOTION_BLOCKING, cand.getX(), cand.getZ());
                    if (topY < world.getSeaLevel() - 1) {
                        continue;
                    }
                    // Пляж — только биом «Пляж»: поля с обрывами по краям не подходят для спавна.
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
                    // Первая же точка от центра с сухой площадкой и водой рядом — спавн.
                    if (dryScore >= 12 && water >= 3) {
                        return top;
                    }
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

    /**

     * Строит точку телепортации в указанной позиции.

     * Структура (вид сверху):

     *   path path path path path

     *   path thin  thin  thin  path

     *   path thin  slab  thin  path

     *   path thin  thin  thin  path

     *   path path path path path

     * Над плитой: base → shaft → capital (красная колонна).

     */


    /**
     * Ищет позицию в биоме травы рядом с указанной точкой на пляже.
     * Сканирует кольцом радиусом 3-8 блоков, ищет траву с хорошим рельефом.
     */
    /**
     * Ищет позицию в биоме teyvat_plains на границе с пляжем.
     * Сканирует от пляжной точки в ЮЖНОМ направлении (от моря к суше),
     * пока не найдёт блок в биоме равнин.
     */
    /**
     * Находит границу пляжа и ставит точку телепортации в 5 блоках от неё в равнинах.
     * Сканирует по оси Z от пляжной позиции, находит последний блок пляжа (beach/beach_edge)
     * и первый блок равнин — это граница. Затем идёт 5 блоков в сторону равнин.
     */
    private static BlockPos findGrassBorderSpawn(ServerWorld world, BlockPos beachPos) {
        int bx = beachPos.getX();
        int bz = beachPos.getZ();
        BlockPos lastBeach = null;

        // Сканируем по Z от пляжа вперёд (от моря к суше), шаг 1 блок
        // Принудительно загружаем чанки чтобы биомы определялись точно
        for (int dz = 0; dz <= 200; dz++) {
            int cx = bx;
            int cz = bz + dz;
            // Гарантируем загрузку чанка
            if (!world.isChunkLoaded(cx >> 4, cz >> 4)) {
                world.getChunk(cx >> 4, cz >> 4);
            }
            BlockPos cand = new BlockPos(cx, 0, cz);
            boolean isBeach = world.getBiome(cand).matchesKey(BEACH_BIOME)
                    || world.getBiome(cand).matchesKey(EDGE_BIOME);
            boolean isGrass = world.getBiome(cand).matchesKey(GRASS_BIOME);

            if (isBeach) {
                lastBeach = new BlockPos(cx, 0, cz);
            }

            if (lastBeach != null && isGrass) {
                // Граница найдена! 5 блоков вперёд в равнинах
                int targetZ = cz + 5;
                int topY = world.getTopY(Heightmap.Type.MOTION_BLOCKING, cx, targetZ);
                BlockPos top = new BlockPos(cx, topY, targetZ);

                if (!world.getFluidState(top).isEmpty() || !world.getFluidState(top.up()).isEmpty()) {
                    for (int offset = -2; offset <= 2; offset++) {
                        int tryZ = targetZ + offset;
                        int tryY = world.getTopY(Heightmap.Type.MOTION_BLOCKING, cx, tryZ);
                        BlockPos tryTop = new BlockPos(cx, tryY, tryZ);
                        if (world.getFluidState(tryTop).isEmpty()
                                && world.getFluidState(tryTop.up()).isEmpty()
                                && world.getBlockState(tryTop.down()).isFullCube(world, tryTop.down())) {
                            top = tryTop;
                            break;
                        }
                    }
                }

                BlockState below = world.getBlockState(top.down());
                if (below.isFullCube(world, top.down())) {
                    LOGGER.info("Граница пляжа: z={}. Точка телепортации: x={}, z={}",
                            lastBeach.getZ(), cx, top.getZ());
                    return top;
                }
            }
        }
        // Фолбэк
        for (int dz = 0; dz <= 60; dz += 4) {
            int cx = bx;
            int cz = bz + dz;
            if (!world.isChunkLoaded(cx >> 4, cz >> 4)) {
                world.getChunk(cx >> 4, cz >> 4);
            }
            BlockPos cand = new BlockPos(cx, 0, cz);
            if (!world.getBiome(cand).matchesKey(GRASS_BIOME)) continue;
            int topY = world.getTopY(Heightmap.Type.MOTION_BLOCKING, cx, cz);
            BlockPos top = new BlockPos(cx, topY, cz);
            if (!world.getFluidState(top).isEmpty() || !world.getFluidState(top.up()).isEmpty()) continue;
            BlockState below = world.getBlockState(top.down());
            if (!below.isFullCube(world, top.down())) continue;
            LOGGER.info("Равнины (фолбэк): x={}, z={}", cx, cz);
            return top;
        }
        return null;
    }

    /**
     * Находит сухую площадку непосредственно на входе серпантина Драконьего хребта.
     * Целевые координаты задаются генератором, поэтому поиск не зависит от случайного
     * порядка загрузки биомов вдоль одной линии.
     */
    private static final int TELEPORT_OFFSET_FROM_TRAIL = 20;


    private static BlockPos findTrailheadSpawn(ServerWorld world, BlockPos beachPos) {
        int centerX = TeyvatDragonRidge.TRAILHEAD_X;
        int centerZ = TeyvatDragonRidge.TRAILHEAD_Z;

        // Используем TRAILHEAD напрямую — не ищем dirt_path
        int trailY = world.getTopY(Heightmap.Type.MOTION_BLOCKING, centerX, centerZ);
        if (trailY < world.getSeaLevel()) {
            trailY = world.getSeaLevel() + 1;
        }
        BlockPos trailCenter = new BlockPos(centerX, trailY, centerZ);

        // Определяем перпендикуляр к тропе: тропа идёт на юг → перпендикуляр = по X
        // Ищем по обе стороны: +X и -X
        for (int sign : new int[]{1, -1}) {
            int tx = centerX + sign * TELEPORT_OFFSET_FROM_TRAIL;
            int tz = centerZ;

            // Загружаем чанк
            world.getChunk(tx >> 4, tz >> 4);

            int topY = world.getTopY(Heightmap.Type.MOTION_BLOCKING, tx, tz);
            if (topY < world.getSeaLevel()) {
                LOGGER.info("Телепорт пропуск (под водой): x={}, z={}", tx, tz);
                continue;
            }

            BlockPos candidate = new BlockPos(tx, topY, tz);

            // Проверяем ровность 5x5
            boolean flat = true;
            for (int sdx = -2; sdx <= 2 && flat; sdx++) {
                for (int sdz = -2; sdz <= 2 && flat; sdz++) {
                    int ny = world.getTopY(Heightmap.Type.MOTION_BLOCKING, tx + sdx, tz + sdz);
                    if (Math.abs(ny - topY) > 3) flat = false;
                }
            }
            if (!flat) {
                LOGGER.info("Телепорт пропуск (неровно): x={}, z={}", tx, tz);
                continue;
            }

            if (!world.getFluidState(candidate).isEmpty()) {
                LOGGER.info("Телепорт пропуск (жидкость): x={}, z={}", tx, tz);
                continue;
            }

            LOGGER.info(">>> ТОЧКА ТЕЛЕПОРТАЦИИ: x={}, z={}, y={}", tx, tz, topY);
            return candidate;
        }

        // Фолбэк: просто offset 20 от TRAILHEAD
        int fx = centerX + TELEPORT_OFFSET_FROM_TRAIL;
        int fz = centerZ;
        int fy = world.getTopY(Heightmap.Type.MOTION_BLOCKING, fx, fz);
        LOGGER.info(">>> ФОЛБЭК ТОЧКА ТЕЛЕПОРТАЦИИ: x={}, z={}, y={}", fx, fz, fy);
        return new BlockPos(fx, fy, fz);
    }

    /** Проверяет, есть ли уже точка телепортации в радиусе radius от center. */
    private static boolean teleportExistsNear(ServerWorld world, BlockPos center, int radius) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -3; dy <= 6; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos check = center.add(dx, dy, dz);
                    BlockState state = world.getBlockState(check);
                    if (state.isOf(TeyvatBlocks.TELEPORT_SLAB_RED)
                            || state.isOf(TeyvatBlocks.TELEPORT_SLAB_BLUE)
                            || state.isOf(TeyvatBlocks.TELEPORT_COLUMN_BASE_RED)
                            || state.isOf(TeyvatBlocks.TELEPORT_COLUMN_BASE_BLUE)
                            || state.isOf(TeyvatBlocks.TELEPORT_COLUMN_CAPITAL_RED)
                            || state.isOf(TeyvatBlocks.TELEPORT_COLUMN_CAPITAL_BLUE)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Строит точку телепортации.
     * center — позиция поверх земли в биоме травы.
     * Кладка — в ямке (y-1), плита на уровне земли (y), колонна над ней.
     */
    public static void buildTeleportPoint(ServerWorld world, BlockPos center) {
        int x = center.getX();
        int y = center.getY();
        int z = center.getZ();

        // Очищаем пространство 5x5x5 над и вокруг точки
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                for (int dy = 1; dy <= 5; dy++) {
                    world.setBlockState(new BlockPos(x + dx, y + dy, z + dz), Blocks.AIR.getDefaultState());
                }
            }
        }

        // Расчищаем траву вокруг на 5x5, чтобы площадка была видна
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                int topY = world.getTopY(Heightmap.Type.MOTION_BLOCKING, x + dx, z + dz);
                BlockPos top = new BlockPos(x + dx, topY, z + dz);
                if (world.getBlockState(top).getBlock() == Blocks.GRASS_BLOCK) {
                    world.setBlockState(top, Blocks.DIRT_PATH.getDefaultState());
                }
            }
        }

        // Строим дорожку от тропы к точке телепортации (по X)
        int trailX = TeyvatDragonRidge.TRAILHEAD_X;
        int trailZ = TeyvatDragonRidge.TRAILHEAD_Z;
        int dir = Integer.compare(x, trailX);
        for (int cx = x; cx != trailX; cx += dir) {
            int tz = z;
            int topY = world.getTopY(Heightmap.Type.MOTION_BLOCKING, cx, tz);
            BlockPos top = new BlockPos(cx, topY, tz);
            var block = world.getBlockState(top).getBlock();
            if (block == Blocks.GRASS_BLOCK || block == Blocks.TALL_GRASS) {
                world.setBlockState(top, Blocks.DIRT_PATH.getDefaultState());
            }
        }

        // Копаем ямку на 1 блок под ромбом
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                if (Math.abs(dx) + Math.abs(dz) <= 3) {
                    world.setBlockState(new BlockPos(x + dx, y - 1, z + dz), Blocks.AIR.getDefaultState());
                }
            }
        }

        // y-1: каменная кладка — ромб r=3 (|dx|+|dz| <= 3)
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                if (Math.abs(dx) + Math.abs(dz) <= 3) {
                    world.setBlockState(new BlockPos(x + dx, y - 1, z + dz), TeyvatBlocks.TELEPORT_PATH.getDefaultState());
                }
            }
        }

        // y-1: тонкое теснение — ромб r=1 (|dx|+|dz| <= 1)
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (Math.abs(dx) + Math.abs(dz) <= 1) {
                    world.setBlockState(new BlockPos(x + dx, y - 1, z + dz), TeyvatBlocks.TELEPORT_PATH_THIN.getDefaultState());
                }
            }
        }

        // y: красная плита в центре
        world.setBlockState(new BlockPos(x, y, z), TeyvatBlocks.TELEPORT_SLAB_RED.getDefaultState());

        // y+1: основание колонны
        world.setBlockState(new BlockPos(x, y + 1, z), TeyvatBlocks.TELEPORT_COLUMN_BASE_RED.getDefaultState());

        // y+2: ствол колонны
        world.setBlockState(new BlockPos(x, y + 2, z), TeyvatBlocks.TELEPORT_COLUMN_SHAFT_RED.getDefaultState());

        // y+3: капитель колонны
        world.setBlockState(new BlockPos(x, y + 3, z), TeyvatBlocks.TELEPORT_COLUMN_CAPITAL_RED.getDefaultState());
    }

}