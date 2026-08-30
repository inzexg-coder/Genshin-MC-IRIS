package net.teyvat;

import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.MapColor;
import net.minecraft.block.PillarBlock;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.block.enums.NoteBlockInstrument;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.util.Identifier;

import net.teyvat.block.SunsettiaLeavesBlock;
import net.teyvat.block.SunsettiaSaplingBlock;
import net.teyvat.block.SunsettiaFruitBlock;

import java.util.ArrayList;
import java.util.List;

/**
 * Дерево Закатника на базе дуба: ствол (PillarBlock), листва, саженец
 * и плод-блок. Текстуры — перекрашенный дуб. Плоды растут в кроне
 * и ломаются кликом, давая предмет Закатник.
 */
public final class TeyvatWood {
    private TeyvatWood() {}

    public static final List<Block> WOOD_BLOCKS = new ArrayList<>();

    public static final Block SUNSETTIA_LOG = register("sunnsettia_log",
            new PillarBlock(AbstractBlock.Settings.create()
                    .registryKey(RegistryKey.of(RegistryKeys.BLOCK, Identifier.of(TeyvatMod.MOD_ID, "sunnsettia_log")))
                    .mapColor(MapColor.SPRUCE_BROWN)
                    .instrument(NoteBlockInstrument.BASS)
                    .strength(2.0f, 3.0f)
                    .sounds(BlockSoundGroup.WOOD)));

    public static final Block SUNSETTIA_LEAVES = register("sunnsettia_leaves",
            new SunsettiaLeavesBlock(AbstractBlock.Settings.create()
                    .registryKey(RegistryKey.of(RegistryKeys.BLOCK, Identifier.of(TeyvatMod.MOD_ID, "sunnsettia_leaves")))
                    .mapColor(MapColor.PALE_GREEN)
                    .strength(0.2f)
                    .sounds(BlockSoundGroup.GRASS)
                    .nonOpaque()
                    .ticksRandomly()
                    .allowsSpawning((state, world, pos, type) -> false)
                    .suffocates((state, world, pos) -> false)
                    .blockVision((state, world, pos) -> false)));

    public static final Block SUNSETTIA_SAPLING = register("sunnsettia_sapling",
            new SunsettiaSaplingBlock(AbstractBlock.Settings.create()
                    .registryKey(RegistryKey.of(RegistryKeys.BLOCK, Identifier.of(TeyvatMod.MOD_ID, "sunnsettia_sapling")))
                    .mapColor(MapColor.PALE_GREEN)
                    .noCollision()
                    .ticksRandomly()
                    .breakInstantly()
                    .sounds(BlockSoundGroup.GRASS)
                    .nonOpaque()));

    public static final Block SUNSETTIA_FRUIT = register("sunnsettia_fruit",
            new SunsettiaFruitBlock(AbstractBlock.Settings.create()
                    .registryKey(RegistryKey.of(RegistryKeys.BLOCK, Identifier.of(TeyvatMod.MOD_ID, "sunnsettia_fruit")))
                    .mapColor(MapColor.ORANGE)
                    .strength(0.2f)
                    .sounds(BlockSoundGroup.GRASS)
                    .nonOpaque()
                    .noCollision()));

    private static <B extends Block> B register(String name, B block) {
        Identifier id = Identifier.of(TeyvatMod.MOD_ID, name);
        Registry.register(Registries.BLOCK, id, block);
        Registry.register(Registries.ITEM, id,
                new BlockItem(block, new Item.Settings()
                        .registryKey(RegistryKey.of(RegistryKeys.ITEM, id))
                        .useBlockPrefixedTranslationKey()));
        WOOD_BLOCKS.add(block);
        return block;
    }

    public static void register() {
        // статические поля регистрируют всё при загрузке класса
    }
}
