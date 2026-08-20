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

import java.util.HashMap;
import java.util.Map;

/**
 * Полноэкранная карта путешественника (как в Genshin): показывает только
 * исследованную территорию, максимально подробно (1 пиксель = 1 блок).
 * Точки телепортации и структуры отмечены значками. Скролл WASD + мышь,
 * зум колёсиком. Открывается клавишей M.
 */
public class MinimapScreen extends Screen {
    /** Пикселей на один блок (зум). */
    private double scale = 1.0;
    private static final double MIN_SCALE = 0.25;
    private static final double MAX_SCALE = 4.0;

    /** Смещение камеры от игрока (в блоках). */
    private double camX = 0, camZ = 0;
    private boolean dragging = false;
    private double lastMouseX, lastMouseY;

    /** Кэш цветов блоков: ключ = worldX * 100000 + worldZ. */
    private final Map<Long, Integer> colorCache = new HashMap<>();
    /** Кэш высот для затенения: ключ = chunkX * 100000 + chunkZ → средняя высота. */
    private final Map<Long, Integer> heightCache = new HashMap<>();

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
        World world = client.world;
        BlockPos playerPos = client.player.getBlockPos();

        // Центр карты — игрок + смещение
        double centerX = playerPos.getX() + camX;
        double centerZ = playerPos.getZ() + camZ;

        // Фон
        ctx.fill(0, 0, sw, sh, 0xFF080C14);

        // Рисуем исследованную территорию (1 пиксель = 1 блок при scale=1)
        int halfW = (int) Math.ceil(sw / 2 / scale) + 2;
        int halfH = (int) Math.ceil(sh / 2 / scale) + 2;

        for (int dx = -halfW; dx <= halfW; dx++) {
            for (int dz = -halfH; dz <= halfH; dz++) {
                int wx = (int) centerX + dx;
                int wz = (int) centerZ + dz;
                int cx = wx >> 4;
                int cz = wz >> 4;

                if (!MinimapRenderer.isExplored(cx, cz)) continue;

                int sx = (int) ((wx - centerX) * scale + sw / 2.0);
                int sy = (int) ((wz - centerZ) * scale + sh / 2.0);
                int size = (int) Math.ceil(scale);
                if (size < 1) size = 1;

                if (sx + size < 0 || sx > sw || sy + size < 0 || sy > sh) continue;

                long key = ((long) wx << 32) | (wz & 0xFFFFFFFFL);
                int color = colorCache.computeIfAbsent(key, k -> getBlockColor(world, wx, wz));
                ctx.fill(sx, sy, sx + size, sy + size, color);
            }
        }

        // Маркеры телепортации (активированные — синие, неактивированные не показываем)
        drawTeleportMarkers(ctx, tr, sw, sh, centerX, centerZ);

        // Игрок — золотой ромб с направлением взгляда
        drawPlayerMarker(ctx, sw, sh, client);

        // Компас
        drawCompass(ctx, tr, sw, sh);

        // Заголовок и координаты
        ctx.drawTextWithShadow(tr, "Карта Тейвата", 10, 8, 0xFFE8C86A);
        String coords = "X: " + playerPos.getX() + "  Y: " + playerPos.getY() + "  Z: " + playerPos.getZ();
        ctx.drawTextWithShadow(tr, coords, 10, 20, 0xFFAAAAAA);

        // Подсказка внизу
        String hint = "WASD / мышь — перемещение · Колёсико — масштаб · Esc — закрыть";
        ctx.drawTextWithShadow(tr, hint, 10, sh - 16, 0x80FFFFFF);
    }

    private void drawTeleportMarkers(DrawContext ctx, TextRenderer tr, int sw, int sh, double cx, double cz) {
        for (long packed : TeleportActivationClient.getActivatedPositionsRaw()) {
            int bx = unpackX(packed);
            int bz = unpackZ(packed);
            int sx = (int) ((bx - cx) * scale + sw / 2.0);
            int sy = (int) ((bz - cz) * scale + sh / 2.0);

            if (sx < -20 || sx > sw + 20 || sy < -20 || sy > sh + 20) continue;

            // Ромб телепорта
            int s = 5;
            ctx.fill(sx - s, sy, sx + s, sy + 1, 0xFF44AAFF);
            ctx.fill(sx - s + 1, sy - 1, sx + s - 1, sy + 2, 0xFF44AAFF);
            ctx.fill(sx - s + 2, sy - 2, sx + s - 2, sy + 3, 0xFF44AAFF);
            ctx.fill(sx - 1, sy - s, sx + 1, sy + s, 0xFF88DDFF);
            ctx.fill(sx - 2, sy - s + 1, sx + 2, sy + s - 1, 0xFF88DDFF);
            // Ядро
            ctx.fill(sx - 1, sy - 1, sx + 1, sy + 1, 0xFFFFFF);
        }
    }

    private void drawPlayerMarker(DrawContext ctx, int sw, int sh, MinecraftClient client) {
        int px = sw / 2;
        int py = sh / 2;
        float yaw = client.player != null ? client.player.getYaw() : 0;

        // Направление взгляда (линия)
        double rad = Math.toRadians(yaw);
        int dx = (int) (-Math.sin(rad) * 8);
        int dz = (int) (Math.cos(rad) * 8);
        ctx.fill(px + dx - 1, py + dz - 1, px + dx + 1, py + dz + 1, 0xFFFFD966);

        // Ромб игрока
        ctx.fill(px - 3, py, px + 3, py + 1, 0xFFE8C86A);
        ctx.fill(px - 2, py - 1, px + 2, py + 2, 0xFFE8C86A);
        ctx.fill(px - 1, py - 2, px + 1, py + 3, 0xFFFFD966);
        ctx.fill(px - 1, py - 1, px + 1, py + 1, 0xFFFFFFFF);
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
        if (click.button() == 0) {
            dragging = true;
            lastMouseX = click.x();
            lastMouseY = click.y();
            return true;
        }
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
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (verticalAmount > 0) {
            scale = Math.min(MAX_SCALE, scale * 1.25);
        } else {
            scale = Math.max(MIN_SCALE, scale / 1.25);
        }
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

    @Override
    public void removed() {
        super.removed();
        colorCache.clear();
        heightCache.clear();
    }

    /** Цвет блока на карте — как ванильная карта: цвет биома + затенение по высоте. */
    private int getBlockColor(World world, int x, int z) {
        try {
            int y = world.getTopY(Heightmap.Type.MOTION_BLOCKING, x, z);
            BlockPos pos = new BlockPos(x, y, z);

            Block block = world.getBlockState(pos.down()).getBlock();
            String biome = world.getBiome(pos).getKey().map(k -> k.getValue().getPath()).orElse("");

            // Вода — по глубине
            if (!world.getFluidState(pos).isEmpty()) {
                int depth = y - world.getSeaLevel();
                if (depth < -8) return shade(0xFF1A3D7A, y);
                if (depth < -3) return shade(0xFF2255AA, y);
                return shade(0xFF2E6BC4, y);
            }

            // Цвет по блоку поверхности + биому
            int base;
            if (block == Blocks.WATER) { base = 0xFF2E6BC4; }
            else if (block == Blocks.SAND || block == Blocks.SANDSTONE) { base = 0xFFE8DCA8; }
            else if (block == Blocks.GRAVEL) { base = 0xFF8F8F86; }
            else if (block == Blocks.STONE || block == Blocks.COBBLESTONE) { base = 0xFF7E7E7E; }
            else if (block instanceof LeavesBlock) { base = 0xFF3A7A28; }
            else if (block == Blocks.GRASS_BLOCK) { base = grassColor(biome); }
            else if (block == Blocks.DIRT || block == Blocks.COARSE_DIRT) { base = 0xFF866043; }
            else if (block == Blocks.PODZOL) { base = 0xFF5B4433; }
            else if (block == Blocks.SNOW || block == Blocks.SNOW_BLOCK || block == Blocks.ICE) { base = 0xFFF0F5FA; }
            else if (block == Blocks.CLAY) { base = 0xFFA0A6B4; }
            else if (block == Blocks.OBSIDIAN) { base = 0xFF150821; }
            else if (block == TeyvatBlocks.TELEPORT_SLAB_RED) { base = 0xFFCC3333; }
            else if (block == TeyvatBlocks.TELEPORT_SLAB_BLUE) { base = 0xFF3388DD; }
            else {
                String name = net.minecraft.registry.Registries.BLOCK.getId(block).getPath();
                if (name.contains("marble")) base = 0xFFDDD5C8;
                else if (name.contains("log") || name.contains("wood")) base = 0xFF6B4E2E;
                else if (name.contains("planks")) base = 0xFF9C7F57;
                else if (name.contains("grass")) base = grassColor(biome);
                else if (name.contains("flower") || name.contains("tulip")) base = 0xFFD4A040;
                else if (name.contains("tall") || name.contains("fern")) base = grassColor(biome);
                else base = grassColor(biome);
            }

            return shade(base, y);
        } catch (Exception e) {
            return 0xFF333333;
        }
    }

    /** Цвет травы в зависимости от биома (как ванильная карта). */
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

    /** Затенение по высоте: выше уровня моря — светлее, ниже — темнее. */
    private static int shade(int argb, int y) {
        int seaLevel = 63;
        float factor = 1.0f;
        if (y > seaLevel) {
            factor = Math.min(1.25f, 1.0f + (y - seaLevel) * 0.008f);
        } else if (y < seaLevel) {
            factor = Math.max(0.55f, 1.0f - (seaLevel - y) * 0.02f);
        }
        int r = (int) (((argb >> 16) & 0xFF) * factor);
        int g = (int) (((argb >> 8) & 0xFF) * factor);
        int b = (int) ((argb & 0xFF) * factor);
        r = Math.min(255, r);
        g = Math.min(255, g);
        b = Math.min(255, b);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static int unpackX(long key) { return (int) (key >> 32); }
    private static int unpackZ(long key) { return (int) (key & 0xFFFFFFFFL); }
}
