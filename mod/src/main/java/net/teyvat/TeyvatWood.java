package net.teyvat;

import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockSetType;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.block.Blocks;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.FenceBlock;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.block.MapColor;
import net.minecraft.block.PillarBlock;
import net.minecraft.block.SaplingBlock;
import net.minecraft.block.SlabBlock;
import net.minecraft.block.StairsBlock;
import net.minecraft.block.TrapdoorBlock;
import net.minecraft.block.WoodType;
import net.minecraft.block.enums.NoteBlockInstrument;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Identifier;

import net.teyvat.block.SunsettiaSaplingBlock;
import net.teyvat.block.SunsettiaFruitBlock;

import java.util.ArrayList;
import java.util.List;

/**
 * Закатник (Sunsettia) — полный новый тип древесины Тейвата.
 * ствол/wood/очищенные варианты, плашки, лестницы, плиты, забор, калитка,
 * дверь, люк, листва, саженец и плод-блок (растёт в кроне, даёт Закатник).
 */
public final class TeyvatWood {
    private TeyvatWood() {}

    public static final List<Block> WOOD_BLOCKS = new ArrayList<>();

    public static final BlockSetType SUNSETTIA_SET = new BlockSetType(
            "sunnsettia", true, true, true,
            BlockSetType.ActivationRule.EVERYTHING,
            BlockSoundGroup.CHERRY_WOOD,
            SoundEvents.BLOCK_WOODEN_DOOR_CLOSE, SoundEvents.BLOCK_WOODEN_DOOR_OPEN,
            SoundEvents.BLOCK_WOODEN_TRAPDOOR_CLOSE, SoundEvents.BLOCK_WOODEN_TRAPDOOR_OPEN,
            SoundEvents.BLOCK_WOODEN_PRESSURE_PLATE_CLICK_OFF, SoundEvents.BLOCK_WOODEN_PRESSURE_PLATE_CLICK_ON,
            SoundEvents.BLOCK_WOODEN_BUTTON_CLICK_OFF, SoundEvents.BLOCK_WOODEN_BUTTON_CLICK_ON);

    public static final WoodType SUNSETTIA_WOOD_TYPE = new WoodType(
            "sunnsettia", SUNSETTIA_SET, BlockSoundGroup.CHERRY_WOOD,
            BlockSoundGroup.CHERRY_WOOD_HANGING_SIGN,
            SoundEvents.BLOCK_FENCE_GATE_CLOSE, SoundEvents.BLOCK_FENCE_GATE_OPEN);

    // ---- ствол и очищенные варианты ----
    public static final Block SUNSETTIA_LOG = log("sunnsettia_log");
    public static final Block SUNSETTIA_WOOD = log("sunnsettia_wood");
    public static final Block STRIPPED_SUNSETTIA_LOG = log("stripped_sunnsettia_log");
    public static final Block STRIPPED_SUNSETTIA_WOOD = log("stripped_sunnsettia_wood");

    // ---- плашки и производные ----
    public static final Block SUNSETTIA_PLANKS = block("sunnsettia_planks");
    public static final Block SUNSETTIA_STAIRS = stairs("sunnsettia_stairs");
    public static final Block SUNSETTIA_SLAB = slab("sunnsettia_slab");
    public static final Block SUNSETTIA_FENCE = fence("sunnsettia_fence");
    public static final Block SUNSETTIA_FENCE_GATE = fenceGate("sunnsettia_fence_gate");
    public static final Block SUNSETTIA_DOOR = door("sunnsettia_door");
    public static final Block SUNSETTIA_TRAPDOOR = trapdoor("sunnsettia_trapdoor");

    // ---- растительность ----
    public static final Block SUNSETTIA_LEAVES = leaves("sunnsettia_leaves");
    public static final Block SUNSETTIA_SAPLING = sapling("sunnsettia_sapling");
    /** Плод, растущий в кроне дерева; ломается — выпадает Закатник. */
    public static final Block SUNSETTIA_FRUIT = fruit("sunnsettia_fruit");

    // ---- settings ----

    private static AbstractBlock.Settings woodSettings(String name) {
        return AbstractBlock.Settings.create()
                .registryKey(RegistryKey.of(RegistryKeys.BLOCK, Identifier.of(TeyvatMod.MOD_ID, name)))
                .mapColor(MapColor.SPRUCE_BROWN)
                .instrument(NoteBlockInstrument.BASS)
                .strength(2.0f, 3.0f)
                .sounds(BlockSoundGroup.CHERRY_WOOD);
    }

    private static AbstractBlock.Settings leavesSettings(String name) {
        return AbstractBlock.Settings.create()
                .registryKey(RegistryKey.of(RegistryKeys.BLOCK, Identifier.of(TeyvatMod.MOD_ID, name)))
                .mapColor(MapColor.PALE_GREEN)
                .strength(0.2f)
                .sounds(BlockSoundGroup.CHERRY_LEAVES)
                .nonOpaque()
                .allowsSpawning((state, world, pos, type) -> false)
                .suffocates((state, world, pos) -> false)
                .blockVision((state, world, pos) -> false);
    }

    private static AbstractBlock.Settings saplingSettings(String name) {
        return AbstractBlock.Settings.create()
                .registryKey(RegistryKey.of(RegistryKeys.BLOCK, Identifier.of(TeyvatMod.MOD_ID, name)))
                .mapColor(MapColor.PALE_GREEN)
                .noCollision()
                .ticksRandomly()
                .breakInstantly()
                .sounds(BlockSoundGroup.CHERRY_SAPLING)
                .nonOpaque();
    }

    private static AbstractBlock.Settings fruitSettings(String name) {
        return AbstractBlock.Settings.create()
                .registryKey(RegistryKey.of(RegistryKeys.BLOCK, Identifier.of(TeyvatMod.MOD_ID, name)))
                .mapColor(MapColor.ORANGE)
                .strength(0.2f)
                .sounds(BlockSoundGroup.CHERRY_SAPLING)
                .nonOpaque()
                .noCollision();
    }

    // ---- constructors ----

    private static Block log(String name) {
        return register(name, new PillarBlock(woodSettings(name)));
    }

    private static Block block(String name) {
        return register(name, new Block(woodSettings(name)));
    }

    private static Block stairs(String name) {
        return register(name, new StairsBlock(SUNSETTIA_PLANKS.getDefaultState(), woodSettings(name)));
    }

    private static Block slab(String name) {
        return register(name, new SlabBlock(woodSettings(name)));
    }

    private static Block fence(String name) {
        return register(name, new FenceBlock(woodSettings(name)));
    }

    private static Block fenceGate(String name) {
        return register(name, new FenceGateBlock(SUNSETTIA_WOOD_TYPE, woodSettings(name)));
    }

    private static Block door(String name) {
        return register(name, new DoorBlock(SUNSETTIA_SET, woodSettings(name).nonOpaque()));
    }

    private static Block trapdoor(String name) {
        return register(name, new TrapdoorBlock(SUNSETTIA_SET, woodSettings(name).nonOpaque()));
    }

    private static Block leaves(String name) {
        return register(name, new SunsettiaLeavesBlock(leavesSettings(name)));
    }

    private static Block sapling(String name) {
        return register(name, new SunsettiaSaplingBlock(saplingSettings(name)));
    }

    private static Block fruit(String name) {
        return register(name, new SunsettiaFruitBlock(fruitSettings(name)));
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

    /** Листва Закатника — простой непрозрачный блок (без ванильного поведения
     *  гниения/осыпания). Дроп саженца идёт через loot table. */
    private static final class SunsettiaLeavesBlock extends Block {
        SunsettiaLeavesBlock(AbstractBlock.Settings settings) {
            super(settings);
        }
    }

    public static void register() {
        // статические поля регистрируют всё при загрузке класса
    }
}
