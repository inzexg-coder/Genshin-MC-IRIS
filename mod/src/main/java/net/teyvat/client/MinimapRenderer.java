package net.teyvat.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;
import net.teyvat.TeyvatBlocks;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Миникарта: открывается по M, показывает исследованную территорию.
 * Рисует цветные прямоугольники (1 чанк = 1 блок на карте).
 */
public final class MinimapRenderer {
    /** Размер миникарты в пикселях. */
    private static final int MAP_SIZE = 180;
    /** Сколько чанков отображается на одной стороне миникарты. */
    private static final int CHUNKS_VIEW = 45;
    /** Пикселей на один чанк. */
    private static final double PX_PER_CHUNK = (double) MAP_SIZE / CHUNKS_VIEW;

    private static boolean isOpen = false;
    /** Исследованные чанки (packed long: x << 32 | z). */
    private static final Set<Long> exploredChunks = new HashSet<>();
    /** Кэш цветов чанков: ключ = packed chunk, value = ARGB color. */
    private static final Map<Long, Integer> chunkColors = new HashMap<>();
    /** Новые чанки для отправки на сервер. */
    private static final List<long[]> pendingNewChunks = new ArrayList<>();

    private MinimapRenderer() {}

    public static void toggle() {
        isOpen = !isOpen;
    }

    public static boolean isVisible() { return isOpen; }

    public static boolean isExplored(int cx, int cz) {
        return exploredChunks.contains(pack(cx, cz));
    }

    public static void explore(ChunkPos pos) {
        long key = pack(pos.x, pos.z);
        if (exploredChunks.add(key)) {
            pendingNewChunks.add(new long[]{pos.x, pos.z});
        }
    }

    public static List<long[]> getAndClearNewChunks() {
        List<long[]> result = new ArrayList<>(pendingNewChunks);
        pendingNewChunks.clear();
        return result;
    }

    public static void loadExplored(Set<String> tags) {
        exploredChunks.clear();
        chunkColors.clear();
        for (String tag : tags) {
            if (tag.startsWith("teyvat:map_")) {
                try {
                    String[] parts = tag.substring("teyvat:map_".length()).split("_");
                    if (parts.length == 2) {
                        exploredChunks.add(pack((int)Long.parseLong(parts[0]), (int)Long.parseLong(parts[1])));
                    }
                } catch (NumberFormatException ignored) {}
            }
        }
    }

    /** Получить теги для сохранения в NBT. */
    public static Set<String> getExploredTags() {
        Set<String> tags = new HashSet<>();
        for (long key : exploredChunks) {
            tags.add("teyvat:map_" + unpackX(key) + "_" + unpackZ(key));
        }
        return tags;
    }

    /** Рендер миникарты — вызывается из InGameHudMixin. */
    public static void render(DrawContext ctx) {
        if (!isOpen) return;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null) return;

        TextRenderer tr = client.textRenderer;
        int sw = client.getWindow().getScaledWidth();
        int sh = client.getWindow().getScaledHeight();

        int mapX = sw / 2 - MAP_SIZE / 2;
        int mapY = sh / 2 - MAP_SIZE / 2;

        // Обновляем исследованные чанки
        updateExplored(client);

        // Тёмный фон
        int pad = 4;
        ctx.fill(mapX - pad, mapY - pad, mapX + MAP_SIZE + pad, mapY + MAP_SIZE + pad, 0xCC0E1320);

        // Золотая рамка
        ctx.fill(mapX - pad, mapY - pad, mapX + MAP_SIZE + pad, mapY - pad + 1, 0xFFE8C86A);
        ctx.fill(mapX - pad, mapY + MAP_SIZE + pad - 1, mapX + MAP_SIZE + pad, mapY + MAP_SIZE + pad, 0xFFE8C86A);
        ctx.fill(mapX - pad, mapY - pad, mapX - pad + 1, mapY + MAP_SIZE + pad, 0xFFE8C86A);
        ctx.fill(mapX + MAP_SIZE + pad - 1, mapY - pad, mapX + MAP_SIZE + pad, mapY + MAP_SIZE + pad, 0xFFE8C86A);

        // Рисуем чанки
        World world = client.world;
        BlockPos playerPos = client.player.getBlockPos();
        int pcx = playerPos.getX() >> 4;
        int pcz = playerPos.getZ() >> 4;
        int halfView = CHUNKS_VIEW / 2;

        for (int dx = -halfView; dx <= halfView; dx++) {
            for (int dz = -halfView; dz <= halfView; dz++) {
                int cx = pcx + dx;
                int cz = pcz + dz;
                long key = pack(cx, cz);

                int screenX = mapX + (int)((dx + halfView) * PX_PER_CHUNK);
                int screenY = mapY + (int)((dz + halfView) * PX_PER_CHUNK);
                int size = (int) Math.ceil(PX_PER_CHUNK);

                if (!exploredChunks.contains(key)) {
                    ctx.fill(screenX, screenY, screenX + size, screenY + size, 0xFF080C14);
                    continue;
                }

                int color = chunkColors.computeIfAbsent(key, k -> calcChunkColor(world, cx, cz));
                ctx.fill(screenX, screenY, screenX + size, screenY + size, color);
            }
        }

        // Маркеры телепортации
        for (long packed : TeleportActivationClient.getActivatedPositionsRaw()) {
            BlockPos pos = new BlockPos(unpackX(packed), 0, unpackZ(packed));
            int tcx = pos.getX() >> 4;
            int tcz = pos.getZ() >> 4;
            int dx = tcx - pcx;
            int dz = tcz - pcz;
            if (Math.abs(dx) <= halfView && Math.abs(dz) <= halfView) {
                int sx = mapX + (int)((dx + halfView) * PX_PER_CHUNK) + (int)(PX_PER_CHUNK / 2) - 2;
                int sy = mapY + (int)((dz + halfView) * PX_PER_CHUNK) + (int)(PX_PER_CHUNK / 2) - 2;
                ctx.fill(sx, sy, sx + 4, sy + 4, 0xFF44AAFF);
                ctx.fill(sx + 1, sy + 1, sx + 3, sy + 3, 0xFF88DDFF);
            }
        }

        // Позиция игрока — жёлтый маркер
        int centerX = mapX + MAP_SIZE / 2;
        int centerY = mapY + MAP_SIZE / 2;
        ctx.fill(centerX - 2, centerY, centerX + 2, centerY + 1, 0xFFE8C86A);
        ctx.fill(centerX - 1, centerY - 1, centerX + 1, centerY + 2, 0xFFFFD966);

        // Заголовок
        String title = "Миникарта";
        int titleW = tr.getWidth(title);
        ctx.drawTextWithShadow(tr, title, mapX + MAP_SIZE / 2 - titleW / 2, mapY - pad - 12, 0xFFE8C86A);
        String hint = "M — закрыть";
        int hintW = tr.getWidth(hint);
        ctx.drawTextWithShadow(tr, hint, mapX + MAP_SIZE / 2 - hintW / 2, mapY + MAP_SIZE + pad + 3, 0x80FFFFFF);
    }

    /** Сканирует загруженные чанки вокруг игрока и отмечает новые как исследованные.
     *  Вызывается из клиентского тика (TeyvatClient) и из MinimapScreen. */
    public static void updateExplored(MinecraftClient client) {
        if (client.world == null || client.player == null) return;
        int px = client.player.getBlockPos().getX() >> 4;
        int pz = client.player.getBlockPos().getZ() >> 4;
        int radius = 2;
        for (int cx = px - radius; cx <= px + radius; cx++) {
            for (int cz = pz - radius; cz <= pz + radius; cz++) {
                if (client.world.isChunkLoaded(cx, cz)) {
                    long key = pack(cx, cz);
                    if (exploredChunks.add(key)) {
                        pendingNewChunks.add(new long[]{cx, cz});
                    }
                }
            }
        }
    }

    /** Вычислить цвет чанка по поверхности. */
    private static int calcChunkColor(World world, int cx, int cz) {
        int bx = (cx << 4) + 8;
        int bz = (cz << 4) + 8;
        int y = world.getTopY(Heightmap.Type.MOTION_BLOCKING, bx, bz);
        BlockPos pos = new BlockPos(bx, y, bz);

        if (!world.getFluidState(pos).isEmpty()) {
            return 0xFF2255AA;
        }
        if (y <= world.getSeaLevel() - 2) {
            return 0xFF1A4488;
        }

        var block = world.getBlockState(pos.down()).getBlock();
        if (block == net.minecraft.block.Blocks.SAND) return 0xFFE8D5A0;
        if (block == net.minecraft.block.Blocks.GRAVEL) return 0xFF999988;
        if (block == net.minecraft.block.Blocks.GRASS_BLOCK) {
            String biome = world.getBiome(pos).getKey().map(k -> k.getValue().getPath()).orElse("");
            return switch (biome) {
                case "teyvat_beach", "teyvat_beach_edge" -> 0xFFE0D090;
                case "starfall_valley" -> 0xFF6F7D62;
                case "starfall_valley_path" -> 0xFFA08B5C;
                case "teyvat_plains" -> 0xFF5B8C3A;
                case "teyvat_lake" -> 0xFF3A7A5C;
                case "teyvat_rocky_sea" -> 0xFF707068;
                default -> 0xFF5B8C3A;
            };
        }
        if (block instanceof net.minecraft.block.LeavesBlock) return 0xFF2D6B1E;
        if (block == net.minecraft.block.Blocks.STONE) return 0xFF808080;
        if (block == TeyvatBlocks.TELEPORT_SLAB_RED || block == TeyvatBlocks.TELEPORT_SLAB_BLUE) return 0xFFFF4444;
        if (block.toString().contains("marble")) return 0xFFD4C8B8;

        String biome = world.getBiome(pos).getKey().map(k -> k.getValue().getPath()).orElse("");
        return switch (biome) {
            case "teyvat_beach" -> 0xFFE8D5A0;
            case "teyvat_beach_edge" -> 0xFFD8C898;
            case "teyvat_plains" -> 0xFF5B8C3A;
            case "teyvat_lake" -> 0xFF3A7A9A;
            case "teyvat_rocky_sea" -> 0xFF707068;
            default -> 0xFF5B8C3A;
        };
    }

    private static long pack(int x, int z) { return ((long) x << 32) | (z & 0xFFFFFFFFL); }
    private static int unpackX(long key) { return (int) (key >> 32); }
    private static int unpackZ(long key) { return (int) (key & 0xFFFFFFFFL); }
}
