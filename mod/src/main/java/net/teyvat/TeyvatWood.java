package net.teyvat;

import net.minecraft.block.*;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.block.AbstractBlock;
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

public final class TeyvatWood {
    private TeyvatWood() {}

    public static final List<Block> WOOD_BLOCKS = new ArrayList<>();

    // === Existing blocks ===

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

    public static final Block SUNSETTIA_FRUIT;
    static {
        Identifier fruitId = Identifier.of(TeyvatMod.MOD_ID, "sunnsettia_fruit");
        SUNSETTIA_FRUIT = Registry.register(Registries.BLOCK, fruitId,
                new SunsettiaFruitBlock(AbstractBlock.Settings.create()
                        .registryKey(RegistryKey.of(RegistryKeys.BLOCK, fruitId))
                        .mapColor(MapColor.ORANGE)
                        .strength(1.0f)
                        .sounds(BlockSoundGroup.GRASS)
                        .nonOpaque()
                        .noCollision()));
    }

    // === New wood blocks ===

    public static final Block SUNSETTIA_WOOD = register("sunnsettia_wood",
            new PillarBlock(AbstractBlock.Settings.create()
                    .registryKey(RegistryKey.of(RegistryKeys.BLOCK, Identifier.of(TeyvatMod.MOD_ID, "sunnsettia_wood")))
                    .mapColor(MapColor.SPRUCE_BROWN)
                    .instrument(NoteBlockInstrument.BASS)
                    .strength(2.0f, 3.0f)
                    .sounds(BlockSoundGroup.WOOD)));

    public static final Block STRIPPED_SUNSETTIA_LOG = register("stripped_sunnsettia_log",
            new PillarBlock(AbstractBlock.Settings.create()
                    .registryKey(RegistryKey.of(RegistryKeys.BLOCK, Identifier.of(TeyvatMod.MOD_ID, "stripped_sunnsettia_log")))
                    .mapColor(MapColor.SPRUCE_BROWN)
                    .instrument(NoteBlockInstrument.BASS)
                    .strength(2.0f, 3.0f)
                    .sounds(BlockSoundGroup.WOOD)));

    public static final Block STRIPPED_SUNSETTIA_WOOD = register("stripped_sunnsettia_wood",
            new PillarBlock(AbstractBlock.Settings.create()
                    .registryKey(RegistryKey.of(RegistryKeys.BLOCK, Identifier.of(TeyvatMod.MOD_ID, "stripped_sunnsettia_wood")))
                    .mapColor(MapColor.SPRUCE_BROWN)
                    .instrument(NoteBlockInstrument.BASS)
                    .strength(2.0f, 3.0f)
                    .sounds(BlockSoundGroup.WOOD)));

    public static final Block SUNSETTIA_PLANKS = register("sunnsettia_planks",
            new Block(AbstractBlock.Settings.create()
                    .registryKey(RegistryKey.of(RegistryKeys.BLOCK, Identifier.of(TeyvatMod.MOD_ID, "sunnsettia_planks")))
                    .mapColor(MapColor.SPRUCE_BROWN)
                    .instrument(NoteBlockInstrument.BASS)
                    .strength(2.0f, 3.0f)
                    .sounds(BlockSoundGroup.WOOD)));

    public static final Block SUNSETTIA_SLAB = register("sunnsettia_slab",
            new SlabBlock(AbstractBlock.Settings.create()
                    .registryKey(RegistryKey.of(RegistryKeys.BLOCK, Identifier.of(TeyvatMod.MOD_ID, "sunnsettia_slab")))
                    .mapColor(MapColor.SPRUCE_BROWN)
                    .instrument(NoteBlockInstrument.BASS)
                    .strength(2.0f, 3.0f)
                    .sounds(BlockSoundGroup.WOOD)));

    public static final Block SUNSETTIA_STAIRS = register("sunnsettia_stairs",
            new StairsBlock(SUNSETTIA_PLANKS.getDefaultState(),
                    AbstractBlock.Settings.create()
                            .registryKey(RegistryKey.of(RegistryKeys.BLOCK, Identifier.of(TeyvatMod.MOD_ID, "sunnsettia_stairs")))
                            .mapColor(MapColor.SPRUCE_BROWN)
                            .instrument(NoteBlockInstrument.BASS)
                            .strength(2.0f, 3.0f)
                            .sounds(BlockSoundGroup.WOOD)));

    public static final Block SUNSETTIA_BUTTON = register("sunnsettia_button",
            new ButtonBlock(BlockSetType.OAK, 30,
                    AbstractBlock.Settings.create()
                            .registryKey(RegistryKey.of(RegistryKeys.BLOCK, Identifier.of(TeyvatMod.MOD_ID, "sunnsettia_button")))
                            .mapColor(MapColor.SPRUCE_BROWN)
                            .noCollision()
                            .strength(0.5f)
                            .sounds(BlockSoundGroup.WOOD)));

    public static final Block SUNSETTIA_PRESSURE_PLATE = register("sunnsettia_pressure_plate",
            new PressurePlateBlock(BlockSetType.OAK,
                    AbstractBlock.Settings.create()
                            .registryKey(RegistryKey.of(RegistryKeys.BLOCK, Identifier.of(TeyvatMod.MOD_ID, "sunnsettia_pressure_plate")))
                            .mapColor(MapColor.SPRUCE_BROWN)
                            .noCollision()
                            .strength(0.5f)
                            .sounds(BlockSoundGroup.WOOD)));

    public static final Block SUNSETTIA_FENCE = register("sunnsettia_fence",
            new FenceBlock(AbstractBlock.Settings.create()
                    .registryKey(RegistryKey.of(RegistryKeys.BLOCK, Identifier.of(TeyvatMod.MOD_ID, "sunnsettia_fence")))
                    .mapColor(MapColor.SPRUCE_BROWN)
                    .instrument(NoteBlockInstrument.BASS)
                    .strength(2.0f, 3.0f)
                    .sounds(BlockSoundGroup.WOOD)));

    public static final Block SUNSETTIA_FENCE_GATE = register("sunnsettia_fence_gate",
            new FenceGateBlock(WoodType.OAK,
                    AbstractBlock.Settings.create()
                            .registryKey(RegistryKey.of(RegistryKeys.BLOCK, Identifier.of(TeyvatMod.MOD_ID, "sunnsettia_fence_gate")))
                            .mapColor(MapColor.SPRUCE_BROWN)
                            .instrument(NoteBlockInstrument.BASS)
                            .strength(2.0f, 3.0f)
                            .sounds(BlockSoundGroup.WOOD)));

    public static final Block SUNSETTIA_DOOR = register("sunnsettia_door",
            new DoorBlock(BlockSetType.OAK,
                    AbstractBlock.Settings.create()
                            .registryKey(RegistryKey.of(RegistryKeys.BLOCK, Identifier.of(TeyvatMod.MOD_ID, "sunnsettia_door")))
                            .mapColor(MapColor.SPRUCE_BROWN)
                            .strength(3.0f)
                            .nonOpaque()
                            .sounds(BlockSoundGroup.WOOD)));

    public static final Block SUNSETTIA_TRAPDOOR = register("sunnsettia_trapdoor",
            new TrapdoorBlock(BlockSetType.OAK,
                    AbstractBlock.Settings.create()
                            .registryKey(RegistryKey.of(RegistryKeys.BLOCK, Identifier.of(TeyvatMod.MOD_ID, "sunnsettia_trapdoor")))
                            .mapColor(MapColor.SPRUCE_BROWN)
                            .strength(3.0f)
                            .nonOpaque()
                            .sounds(BlockSoundGroup.WOOD)));

    // Signs don't register a BlockItem via register() — handled separately
    public static final Block SUNSETTIA_SIGN;
    public static final Block SUNSETTIA_WALL_SIGN;
    public static final Block SUNSETTIA_HANGING_SIGN;
    public static final Block SUNSETTIA_WALL_HANGING_SIGN;
    static {
        Identifier signId = Identifier.of(TeyvatMod.MOD_ID, "sunnsettia_sign");
        SUNSETTIA_SIGN = Registry.register(Registries.BLOCK, signId,
                new SignBlock(WoodType.OAK,
                        AbstractBlock.Settings.create()
                                .registryKey(RegistryKey.of(RegistryKeys.BLOCK, signId))
                                .mapColor(MapColor.SPRUCE_BROWN)
                                .instrument(NoteBlockInstrument.BASS)
                                .strength(1.0f)
                                .sounds(BlockSoundGroup.WOOD)
                                .nonOpaque()));

        Identifier wallSignId = Identifier.of(TeyvatMod.MOD_ID, "sunnsettia_wall_sign");
        SUNSETTIA_WALL_SIGN = Registry.register(Registries.BLOCK, wallSignId,
                new WallSignBlock(WoodType.OAK,
                        AbstractBlock.Settings.create()
                                .registryKey(RegistryKey.of(RegistryKeys.BLOCK, wallSignId))
                                .mapColor(MapColor.SPRUCE_BROWN)
                                .instrument(NoteBlockInstrument.BASS)
                                .strength(1.0f)
                                .sounds(BlockSoundGroup.WOOD)
                                .nonOpaque()
                                .dropsNothing()));

        Registry.register(Registries.ITEM, signId,
                new Item(new Item.Settings().registryKey(RegistryKey.of(RegistryKeys.ITEM, signId))));

        Identifier hSignId = Identifier.of(TeyvatMod.MOD_ID, "sunnsettia_hanging_sign");
        SUNSETTIA_HANGING_SIGN = Registry.register(Registries.BLOCK, hSignId,
                new HangingSignBlock(WoodType.OAK,
                        AbstractBlock.Settings.create()
                                .registryKey(RegistryKey.of(RegistryKeys.BLOCK, hSignId))
                                .mapColor(MapColor.SPRUCE_BROWN)
                                .instrument(NoteBlockInstrument.BASS)
                                .strength(1.0f)
                                .sounds(BlockSoundGroup.WOOD)
                                .nonOpaque()));

        Identifier whSignId = Identifier.of(TeyvatMod.MOD_ID, "sunnsettia_wall_hanging_sign");
        SUNSETTIA_WALL_HANGING_SIGN = Registry.register(Registries.BLOCK, whSignId,
                new WallHangingSignBlock(WoodType.OAK,
                        AbstractBlock.Settings.create()
                                .registryKey(RegistryKey.of(RegistryKeys.BLOCK, whSignId))
                                .mapColor(MapColor.SPRUCE_BROWN)
                                .instrument(NoteBlockInstrument.BASS)
                                .strength(1.0f)
                                .sounds(BlockSoundGroup.WOOD)
                                .nonOpaque()
                                .dropsNothing()));

        Registry.register(Registries.ITEM, hSignId,
                new Item(new Item.Settings().registryKey(RegistryKey.of(RegistryKeys.ITEM, hSignId))));
    }

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
