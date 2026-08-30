package net.teyvat.worldgen;

import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
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
 * Дерево Закатника для генерации мира. Ставится на верхней поверхности
 * и рисует среднее дерево с плодами. Работает только в границах чанка.
 */
public final class SunsettiaTreeFeature extends Feature<DefaultFeatureConfig> {
    public static final Identifier ID = Identifier.of(TeyvatMod.MOD_ID, "sunnsettia_tree");

    public SunsettiaTreeFeature() {
        super(DefaultFeatureConfig.CODEC);
    }

    public static void register() {
        Registry.register(Registries.FEATURE, ID, new SunsettiaTreeFeature());
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
        // другого дерева: иначе саженцы Закатника лезут поверх деревьев.
        var surface = world.getBlockState(root);
        var below = world.getBlockState(root.down());
        if (!surface.isIn(BlockTags.DIRT) && !below.isIn(BlockTags.DIRT)) {
            return false;
        }
        // рисуем дерево (общий пласер)
        return SunsettiaTreePlacer.grow(world, root, random);
    }
}
