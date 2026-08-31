package net.teyvat.worldgen;

import net.minecraft.block.BlockState;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.gen.feature.DefaultFeatureConfig;
import net.minecraft.world.gen.feature.Feature;
import net.minecraft.world.gen.feature.util.FeatureContext;
import net.teyvat.TeyvatMod;
import net.teyvat.block.SunsettiaTreePlacer;

/**
 * Дерево Закатника для генерации мира. Несколько стилей как у дубов:
 * sunnsettia_tree (компактное), sunnsettia_tree_large (широкое),
 * sunnsettia_tree_tall (высокое). Работает только в границах чанка
 * и только на настоящей земле с чистым местом под ствол.
 */
public final class SunsettiaTreeFeature extends Feature<DefaultFeatureConfig> {
    private final SunsettiaTreePlacer.Style style;

    public SunsettiaTreeFeature(SunsettiaTreePlacer.Style style) {
        super(DefaultFeatureConfig.CODEC);
        this.style = style;
    }

    public static void register() {
        for (SunsettiaTreePlacer.Style style : SunsettiaTreePlacer.Style.values()) {
            Registry.register(Registries.FEATURE,
                    Identifier.of(TeyvatMod.MOD_ID, "sunnsettia_tree_" + style.name().toLowerCase()),
                    new SunsettiaTreeFeature(style));
        }
    }

    @Override
    public boolean generate(FeatureContext<DefaultFeatureConfig> context) {
        var world = context.getWorld();
        var random = context.getRandom();
        var origin = context.getOrigin();

        int x = origin.getX();
        int z = origin.getZ();
        ChunkPos chunkPos = new ChunkPos(origin);
        int cMinX = chunkPos.getStartX();
        int cMinZ = chunkPos.getStartZ();
        int cMaxX = cMinX + 15;
        int cMaxZ = cMinZ + 15;

        // Дерево должно помещаться в чанке с учётом кроны (~3 блока).
        if (x - 3 < cMinX || x + 3 > cMaxX || z - 3 < cMinZ || z + 3 > cMaxZ) return false;

        int groundY = world.getTopY(Heightmap.Type.WORLD_SURFACE, x, z);
        if (groundY <= world.getBottomY() + 8) return false;

        BlockPos root = new BlockPos(x, groundY, z);
        // Дерево должно стоять на настоящей земле (трава/дерн), а не на кроне
        // другого дерева.
        var surface = world.getBlockState(root);
        var below = world.getBlockState(root.down());
        if (!surface.isIn(BlockTags.DIRT) && !below.isIn(BlockTags.DIRT)) {
            return false;
        }
        // Стволу нужно чистое место вверх: не растим внутри чужой кроны.
        for (int dy = 1; dy <= 12; dy++) {
            BlockState s = world.getBlockState(root.up(dy));
            if (!s.isAir() && !s.getBlock().getDefaultState().isReplaceable()) {
                return false;
            }
        }

        // рисуем дерево (общий пласер со стилем)
        return SunsettiaTreePlacer.grow(world, root, random, style);
    }
}
