package net.teyvat.client;

import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.LeavesBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;
import net.teyvat.TeyvatBlocks;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Полноэкранная карта путешественника. Оптимизирована:
 * каждый исследованный чанк пре-рендерится ОДИН раз в массив 16×16 цветов,
 * затем рисуется как готовые прямоугольники. Никаких world-запросов в кадре.
 */
public class MinimapScreen extends Screen {
    private double scale = 1.0;
    private static final double MIN_SCALE = 0.25;
    private static final double MAX_SCALE = 4.0;

    private double camX = 0, camZ = 0;
    private boolean dragging = false;

    /** Пре-рендер чанков: ключ = chunkX<<32|chunkZ → int[256] (16×16 ARGB). */
    private static final Map<Long, int[]> chunkPixels = new ConcurrentHashMap<>();
    /** Последняя позиция игрока — для сброса кэша при смене чанка. */
    private int lastPlayerChunkX = Integer.MIN_VALUE;
    private int lastPlayerChunkZ = Integer.MIN_VALUE;
    /** Таймер периодического обновления кэша (тики). */
    private int refreshTimer = 0;

    public MinimapScreen() {
        super(Text.literal("Карта"));
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        MinecraftClient client = this.client;
        if (client.player == null || client.world == null) return;

        TextRenderer tr = client.textRenderer;
        int sw = client.getWindow().getScaledWidth();
        int sh = client.getWindow().getScaledHeight();
        BlockPos playerPos = client.player.getBlockPos();

        // Сброс кэша при переходе в новый чанк — чтобы карта обновилась
        int pcx = playerPos.getX() >> 4;
        int pcz = playerPos.getZ() >> 4;
        if (pcx != lastPlayerChunkX || pcz != lastPlayerChunkZ) {
            lastPlayerChunkX = pcx;
            lastPlayerChunkZ = pcz;
            chunkPixels.clear();
        }

        // Периодическое обновление: раз в 40 тиков (2 сек) сбрасываем кэш чанков рядом
        refreshTimer++;
        if (refreshTimer >= 40) {
            refreshTimer = 0;
            chunkPixels.clear();
        }

        double centerX = playerPos.getX() + camX;
        double centerZ = playerPos.getZ() + camZ;

        ctx.fill(0, 0, sw, sh, 0xFF080C14);

        drawChunks(ctx, client.world, sw, sh, centerX, centerZ);
        drawTeleportMarkers(ctx, tr, sw, sh, centerX, centerZ);
        drawPlayerMarker(ctx, sw, sh, client, centerX, centerZ);
        drawCompass(ctx, tr, sw, sh);

        ctx.drawTextWithShadow(tr, "Карта Тейвата", 10, 8, 0xFFE8C86A);
        String coords = "X: " + playerPos.getX() + "  Y: " + playerPos.getY() + "  Z: " + playerPos.getZ();
        ctx.drawTextWithShadow(tr, coords, 10, 20, 0xFFAAAAAA);
        String hint = "WASD / мышь — перемещение · Колёсико — масштаб · Esc — закрыть";
        ctx.drawTextWithShadow(tr, hint, 10, sh - 16, 0x80FFFFFF);
    }

    /** Рисует видимые чанки из пре-рендер кэша. */
    private void drawChunks(DrawContext ctx, World world, int sw, int sh, double cx, double cz) {
        // Видимые чанки (по границам экрана)
        double halfWorldW = sw / 2 / scale + 16;
        double halfWorldH = sh / 2 / scale + 16;
        int minCx = (int) ((cx - halfWorldW) / 16) - 1;
        int maxCx = (int) ((cx + halfWorldW) / 16) + 1;
        int minCz = (int) ((cz - halfWorldH) / 16) - 1;
        int maxCz = (int) ((cz + halfWorldH) / 16) + 1;

        for (int ccx = minCx; ccx <= maxCx; ccx++) {
            for (int ccz = minCz; ccz <= maxCz; ccz++) {
                if (!MinimapRenderer.isExplored(ccx, ccz)) continue;

                long key = chunkKey(ccx, ccz);
                int[] pixels = chunkPixels.get(key);
                if (pixels == null) {
                    pixels = renderChunk(world, ccx, ccz);
                    chunkPixels.put(key, pixels);
                }

                // Экранные координаты чанка
                double chunkScreenX = (ccx * 16 - cx) * scale + sw / 2.0;
                double chunkScreenY = (ccz * 16 - cz) * scale + sh / 2.0;
                double pxSize = scale; // размер одного блока в пикселях

                if (pxSize >= 2) {
                    // Достаточно крупно — рисуем попиксельно (256 fill на чанк)
                    for (int py = 0; py < 16; py++) {
                        for (int pxx = 0; pxx < 16; pxx++) {
                            int sx = (int) (chunkScreenX + pxx * pxSize);
                            int sy = (int) (chunkScreenY + py * pxSize);
                            int size = (int) Math.ceil(pxSize);
                            if (sx + size < 0 || sx > sw || sy + size < 0 || sy > sh) continue;
                            ctx.fill(sx, sy, sx + size, sy + size, pixels[py * 16 + pxx]);
                        }
                    }
                } else {
                    // Мелко — один прямоугольник на чанк (средний цвет)
                    int avg = averageColor(pixels);
                    int sx = (int) chunkScreenX;
                    int sy = (int) chunkScreenY;
                    int size = (int) Math.ceil(16 * scale);
                    if (sx + size < 0 || sx > sw || sy + size < 0 || sy > sh) continue;
                    ctx.fill(sx, sy, sx + size, sy + size, avg);
                }
            }
        }
    }

    /** Пре-рендер одного чанка в 16×16 пикселей (вызывается один раз). */
    private static int[] renderChunk(World world, int ccx, int ccz) {
        int[] pixels = new int[256];
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                int wx = (ccx << 4) + lx;
                int wz = (ccz << 4) + lz;
                pixels[lz * 16 + lx] = computeBlockColor(world, wx, wz);
            }
        }
        return pixels;
    }

    private static long chunkKey(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    private static int averageColor(int[] pixels) {
        long r = 0, g = 0, b = 0;
        for (int p : pixels) {
            r += (p >> 16) & 0xFF;
            g += (p >> 8) & 0xFF;
            b += p & 0xFF;
        }
        int n = pixels.length;
        return 0xFF000000 | ((int) (r / n) << 16) | ((int) (g / n) << 8) | (int) (b / n);
    }

    private void drawTeleportMarkers(DrawContext ctx, TextRenderer tr, int sw, int sh, double cx, double cz) {
        for (long packed : TeleportActivationClient.getActivatedPositionsRaw()) {
            int bx = unpackX(packed);
            int bz = unpackZ(packed);
            int sx = (int) ((bx - cx) * scale + sw / 2.0);
            int sy = (int) ((bz - cz) * scale + sh / 2.0);
            if (sx < -30 || sx > sw + 30 || sy < -30 || sy > sh + 30) continue;
            // Крупный значок телепортации: синий ромб с белой обводкой и ядром
            int r = 10; // радиус ромба
            // Внешняя обводка (белая)
            for (int i = -r; i <= r; i++) {
                int w = r - Math.abs(i);
                ctx.fill(sx - w - 1, sy + i, sx + w + 1, sy + i + 1, 0xFFFFFFFF);
            }
            // Тело ромба (синее)
            for (int i = -r + 1; i <= r - 1; i++) {
                int w = (r - 1) - Math.abs(i);
                ctx.fill(sx - w, sy + i, sx + w, sy + i + 1, 0xFF3388DD);
            }
            // Внутреннее свечение (голубое)
            for (int i = -r / 2; i <= r / 2; i++) {
                int w = (r / 2) - Math.abs(i);
                ctx.fill(sx - w, sy + i, sx + w, sy + i + 1, 0xFF66BBFF);
            }
            // Ядро (белое)
            ctx.fill(sx - 2, sy - 2, sx + 2, sy + 2, 0xFFFFFFFF);
        }
    }

    /** Маркер игрока — рисуется в мировой позиции (двигается при перетаскивании карты). */
    private void drawPlayerMarker(DrawContext ctx, int sw, int sh, MinecraftClient client, double cx, double cz) {
        BlockPos playerPos = client.player.getBlockPos();
        int px = (int) ((playerPos.getX() + 0.5 - cx) * scale + sw / 2.0);
        int py = (int) ((playerPos.getZ() + 0.5 - cz) * scale + sh / 2.0);
        float yaw = client.player != null ? client.player.getYaw() : 0;
        double rad = Math.toRadians(yaw);

        // Крупный маркер игрока: золотая стрелка с белым ядром и тёмной обводкой
        int r = 12; // размер стрелки

        // Направление взгляда — линия от центра
        int tipX = px + (int) (-Math.sin(rad) * r);
        int tipY = py + (int) (Math.cos(rad) * r);

        // Стрелка: треугольник из трёх линий
        int leftX = px + (int) (-Math.sin(rad + 2.5) * r * 0.6);
        int leftY = py + (int) (Math.cos(rad + 2.5) * r * 0.6);
        int rightX = px + (int) (-Math.sin(rad - 2.5) * r * 0.6);
        int rightY = py + (int) (Math.cos(rad - 2.5) * r * 0.6);

        // Обводка (белая)
        drawLine(ctx, px, py, tipX, tipY, 4, 0xFFFFFFFF);
        drawLine(ctx, leftX, leftY, tipX, tipY, 4, 0xFFFFFFFF);
        drawLine(ctx, rightX, rightY, tipX, tipY, 4, 0xFFFFFFFF);

        // Заливка (золотая)
        drawLine(ctx, px, py, tipX, tipY, 2, 0xFFFFD966);
        drawLine(ctx, leftX, leftY, tipX, tipY, 2, 0xFFE8C86A);
        drawLine(ctx, rightX, rightY, tipX, tipY, 2, 0xFFE8C86A);

        // Ядро (белый круг)
        for (int dy = -3; dy <= 3; dy++) {
            int w = 3 - Math.abs(dy);
            ctx.fill(px - w, py + dy, px + w + 1, py + dy + 1, 0xFFFFFFFF);
        }
    }

    /** Рисует толстую линию между двумя точками. */
    private static void drawLine(DrawContext ctx, int x0, int y0, int x1, int y1, int thickness, int color) {
        int dx = Math.abs(x1 - x0);
        int dy = Math.abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1;
        int sy = y0 < y1 ? 1 : -1;
        int err = dx - dy;
        int half = thickness / 2;
        while (true) {
            ctx.fill(x0 - half, y0 - half, x0 + half + 1, y0 + half + 1, color);
            if (x0 == x1 && y0 == y1) break;
            int e2 = 2 * err;
            if (e2 > -dy) { err -= dy; x0 += sx; }
            if (e2 < dx) { err += dx; y0 += sy; }
        }
    }

    private void drawCompass(DrawContext ctx, TextRenderer tr, int sw, int sh) {
        int midX = sw / 2;
        int midY = sh / 2;
        ctx.drawTextWithShadow(tr, "С", midX - 3, midY - 40, 0xFFE8C86A);
        ctx.drawTextWithShadow(tr, "Ю", midX - 3, midY + 34, 0xFF9AA5B8);
        ctx.drawTextWithShadow(tr, "З", midX - 40, midY - 4, 0xFF9AA5B8);
        ctx.drawTextWithShadow(tr, "В", midX + 34, midY - 4, 0xFF9AA5B8);
    }

    @Override
    public boolean mouseClicked(Click click, boolean bl) {
        if (click.button() == 0) { dragging = true; return true; }
        return super.mouseClicked(click, bl);
    }

    @Override
    public boolean mouseReleased(Click click) {
        if (click.button() == 0) dragging = false;
        return super.mouseReleased(click);
    }

    @Override
    public boolean mouseDragged(Click click, double offsetX, double offsetY) {
        if (dragging) {
            camX -= offsetX / scale;
            camZ -= offsetY / scale;
            return true;
        }
        return super.mouseDragged(click, offsetX, offsetY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double h, double v) {
        scale = v > 0 ? Math.min(MAX_SCALE, scale * 1.25) : Math.max(MIN_SCALE, scale / 1.25);
        return true;
    }

    @Override
    public void tick() {
        super.tick();
        MinecraftClient client = this.client;
        if (client.player == null) return;
        double speed = 12.0 / scale;
        if (client.options.forwardKey.isPressed()) camZ -= speed;
        if (client.options.backKey.isPressed()) camZ += speed;
        if (client.options.leftKey.isPressed()) camX -= speed;
        if (client.options.rightKey.isPressed()) camX += speed;
    }

    @Override
    public boolean keyPressed(KeyInput key) {
        if (key.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_M || key.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
            this.close();
            return true;
        }
        return super.keyPressed(key);
    }

    @Override
    public boolean shouldPause() { return false; }

    /** Вычисление цвета блока — вызывается только при пре-рендере чанка. */
    private static int computeBlockColor(World world, int x, int z) {
        try {
            // OCEAN_FLOOR игнорирует воду → возвращает уровень дна
            int floorY = world.getTopY(Heightmap.Type.OCEAN_FLOOR, x, z);
            // WORLD_SURFACE возвращает верхний непустой блок (включая воду)
            int surfaceY = world.getTopY(Heightmap.Type.WORLD_SURFACE, x, z);

            // Если дно ниже уровня моря → вода (любой глубины, включая 1 блок у пляжа)
            if (floorY < 62) {
                return 0xFF1A3D7A;
            }

            BlockPos pos = new BlockPos(x, floorY, z);
            Block block = world.getBlockState(pos.down()).getBlock();
            String biome = world.getBiome(pos).getKey().map(k -> k.getValue().getPath()).orElse("");

            // Дополнительная проверка на воду (озёра выше уровня моря)
            if (!world.getFluidState(new BlockPos(x, surfaceY - 1, z)).isEmpty()) {
                return 0xFF1A3D7A;
            }

            int shadeY = floorY;

            int base;
            if (block == Blocks.SAND || block == Blocks.SANDSTONE) base = 0xFFE8DCA8;
            else if (block == Blocks.GRAVEL) base = 0xFF8F8F86;
            else if (block == Blocks.STONE || block == Blocks.COBBLESTONE) base = 0xFF7E7E7E;
            else if (block instanceof LeavesBlock) base = 0xFF3A7A28;
            else if (block == Blocks.GRASS_BLOCK) base = grassColor(biome);
            else if (block == Blocks.DIRT || block == Blocks.COARSE_DIRT) base = 0xFF866043;
            else if (block == Blocks.SNOW || block == Blocks.SNOW_BLOCK || block == Blocks.ICE) base = 0xFFF0F5FA;
            else if (block == TeyvatBlocks.TELEPORT_SLAB_RED) base = 0xFFCC3333;
            else if (block == TeyvatBlocks.TELEPORT_SLAB_BLUE) base = 0xFF3388DD;
            else {
                String name = net.minecraft.registry.Registries.BLOCK.getId(block).getPath();
                if (name.contains("marble")) base = 0xFFDDD5C8;
                else if (name.contains("log") || name.contains("wood")) base = 0xFF6B4E2E;
                else if (name.contains("planks")) base = 0xFF9C7F57;
                else base = grassColor(biome);
            }
            return shade(base, shadeY);
        } catch (Exception e) {
            return 0xFF333333;
        }
    }

    private static int grassColor(String biome) {
        return switch (biome) {
            case "teyvat_beach", "teyvat_beach_edge" -> 0xFFD4C87A;
            case "teyvat_plains" -> 0xFF91BD59;
            case "teyvat_lake" -> 0xFF6BA85A;
            case "teyvat_outskirts" -> 0xFF86B05A;
            case "teyvat_rocky_sea" -> 0xFF8A9A6A;
            default -> 0xFF91BD59;
        };
    }

    private static int shade(int argb, int y) {
        int seaLevel = 63;
        float factor = y > seaLevel ? Math.min(1.25f, 1.0f + (y - seaLevel) * 0.008f)
                : y < seaLevel ? Math.max(0.55f, 1.0f - (seaLevel - y) * 0.02f) : 1.0f;
        int r = Math.min(255, (int) (((argb >> 16) & 0xFF) * factor));
        int g = Math.min(255, (int) (((argb >> 8) & 0xFF) * factor));
        int b = Math.min(255, (int) ((argb & 0xFF) * factor));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static int unpackX(long key) { return (int) (key >> 32); }
    private static int unpackZ(long key) { return (int) (key & 0xFFFFFFFFL); }
}
