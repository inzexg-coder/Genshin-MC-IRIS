package net.teyvat;

import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.block.Block;
import net.minecraft.block.BlockSetType;
import net.minecraft.block.Blocks;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.FenceBlock;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.block.MapColor;
import net.minecraft.block.PillarBlock;
import net.minecraft.block.SlabBlock;
import net.minecraft.block.StairsBlock;
import net.minecraft.block.WallBlock;
import net.minecraft.block.WoodType;
import net.minecraft.block.enums.NoteBlockInstrument;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemGroups;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import net.teyvat.block.MarbleArchBlock;
import net.teyvat.item.TeyvatItems;
import net.teyvat.block.MarbleSideStairsBlock;

import java.util.ArrayList;
import java.util.List;

public final class TeyvatBlocks {
    private TeyvatBlocks() {}

    public static final List<Block> ALL_BLOCKS = new ArrayList<>();

    // ---- base blocks ----
    public static final Block MARBLE = block("marble");
    public static final Block POLISHED_MARBLE = block("polished_marble");
    public static final Block MARBLE_BRICKS = block("marble_bricks");
    public static final Block MARBLE_TILES = block("marble_tiles");
    public static final Block CHISELED_MARBLE = block("chiseled_marble");
    public static final Block GOLD_TRIMMED_MARBLE = block("gold_trimmed_marble");

    // ---- columns / pillars ----
    public static final Block MARBLE_PILLAR = pillar("marble_pillar");
    public static final Block MARBLE_COLUMN = nonOpaque("marble_column");
    public static final Block MARBLE_COLUMN_SMALL = nonOpaque("marble_column_small");
    public static final Block MARBLE_COLUMN_BASE = nonOpaque("marble_column_base");
    public static final Block MARBLE_COLUMN_MID = nonOpaque("marble_column_mid");
    public static final Block MARBLE_COLUMN_CAPITAL = nonOpaque("marble_column_capital");
    public static final Block MARBLE_PEDESTAL = nonOpaque("marble_pedestal");

    // ---- teleport structure blocks ----
    public static final Block TELEPORT_PATH = block("teleport_path");
    public static final Block TELEPORT_PATH_THIN = block("teleport_path_thin");
    public static final Block TELEPORT_SLAB_RED = register("teleport_slab_red", new SlabBlock(settings("teleport_slab_red").luminance(state -> 15)));
    public static final Block TELEPORT_SLAB_BLUE = register("teleport_slab_blue", new SlabBlock(settings("teleport_slab_blue").luminance(state -> 15)));
    public static final Block TELEPORT_COLUMN_BASE_RED = register("teleport_column_base_red", new Block(settings("teleport_column_base_red").luminance(state -> 7)));
    public static final Block TELEPORT_COLUMN_BASE_BLUE = register("teleport_column_base_blue", new Block(settings("teleport_column_base_blue").luminance(state -> 15)));
    public static final Block TELEPORT_COLUMN_SHAFT_RED = register("teleport_column_shaft_red", new Block(settings("teleport_column_shaft_red").luminance(state -> 7)));
    public static final Block TELEPORT_COLUMN_SHAFT_BLUE = register("teleport_column_shaft_blue", new Block(settings("teleport_column_shaft_blue").luminance(state -> 15)));
    public static final Block TELEPORT_COLUMN_CAPITAL_RED = register("teleport_column_capital_red", new Block(settings("teleport_column_capital_red").luminance(state -> 7)));
    public static final Block TELEPORT_COLUMN_CAPITAL_BLUE = register("teleport_column_capital_blue", new Block(settings("teleport_column_capital_blue").luminance(state -> 15)));

    // ---- stairs / slabs ----
    public static final Block MARBLE_STAIRS = stairs("marble_stairs", MARBLE);
    public static final Block POLISHED_MARBLE_STAIRS = stairs("polished_marble_stairs", POLISHED_MARBLE);
    public static final Block MARBLE_BRICK_STAIRS = stairs("marble_brick_stairs", MARBLE_BRICKS);
    public static final Block MARBLE_TILE_STAIRS = stairs("marble_tile_stairs", MARBLE_TILES);
    public static final Block MARBLE_SLAB = slab("marble_slab");
    public static final Block POLISHED_MARBLE_SLAB = slab("polished_marble_slab");
    public static final Block MARBLE_BRICK_SLAB = slab("marble_brick_slab");
    public static final Block MARBLE_TILE_SLAB = slab("marble_tile_slab");

    // ---- fences / walls ----
    public static final Block MARBLE_WALL = wall("marble_wall");
    public static final Block MARBLE_FENCE = fence("marble_fence");
    public static final Block MARBLE_FENCE_GATE = fenceGate("marble_fence_gate");

    // ---- beams / special ----
    public static final Block MARBLE_BEAM = pillar("marble_beam");
    public static final Block MARBLE_SIDE_STAIRS = sideStairs("marble_side_stairs");
    public static final Block MARBLE_ARCH = arch("marble_arch");
    public static final Block MARBLE_GATE = block("marble_gate");
    public static final Block MARBLE_DOOR = door("marble_door");
    public static final Block MARBLE_LAMP = lamp("marble_lamp");

    private static Block.Settings settings(String name) {
        return Block.Settings.create()
                .registryKey(RegistryKey.of(RegistryKeys.BLOCK, Identifier.of(TeyvatMod.MOD_ID, name)))
                .mapColor(MapColor.OFF_WHITE)
                .instrument(NoteBlockInstrument.BASEDRUM)
                .strength(3.0f, 6.0f)
                .sounds(BlockSoundGroup.STONE);
    }

        private static Block block(String name) {
        return register(name, new Block(settings(name)));
    }

    /** Непрозрачный соседям: грани позади тонких колонн рендерятся (как у заборов), без дыр в пустоту. */
    private static Block nonOpaque(String name) {
        return register(name, new Block(settings(name).nonOpaque()));
    }


    private static Block pillar(String name) {
        return register(name, new PillarBlock(settings(name)));
    }

    private static Block stairs(String name, Block base) {
        return register(name, new StairsBlock(base.getDefaultState(), settings(name)));
    }

    private static Block slab(String name) {
        return register(name, new SlabBlock(settings(name)));
    }

    private static Block wall(String name) {
        return register(name, new WallBlock(settings(name)));
    }

    private static Block fence(String name) {
        return register(name, new FenceBlock(settings(name)));
    }

    private static Block fenceGate(String name) {
        return register(name, new FenceGateBlock(WoodType.OAK, settings(name)));
    }

    /** Мраморная дверь: стандартная ванильная 2-блочная, открывается мгновенно как oak_door. */
    private static Block arch(String name) {
        // nonOpaque: блок под аркой не теряет верхнюю грань (не видно дыры сквозь проём)
        return register(name, new MarbleArchBlock(settings(name).nonOpaque()));
    }

    private static Block door(String name) {
        return register(name, new DoorBlock(BlockSetType.STONE, settings(name).nonOpaque()));
    }

    private static Block sideStairs(String name) {
        return register(name, new MarbleSideStairsBlock(settings(name)));
    }

    private static Block lamp(String name) {
        return register(name, new Block(settings(name).luminance(state -> 13)));
    }

    private static <B extends Block> B register(String name, B block) {
        Identifier id = Identifier.of(TeyvatMod.MOD_ID, name);
        Registry.register(Registries.BLOCK, id, block);
        Registry.register(Registries.ITEM, id,
                new BlockItem(block, new Item.Settings()
                        .registryKey(RegistryKey.of(RegistryKeys.ITEM, id))
                        .useBlockPrefixedTranslationKey()));
        ALL_BLOCKS.add(block);
        return block;
    }

    public static void register() {
        // static init above registers everything
    }

    public static void registerItemGroup() {
        ItemGroup group = FabricItemGroup.builder()
                .icon(() -> new ItemStack(MARBLE))
                .displayName(Text.translatable("itemGroup.teyvat.blocks"))
                .entries((context, entries) -> {
                    for (Block block : ALL_BLOCKS) {
                        entries.add(block);
                    }
                    for (Item item : TeyvatItems.ALL) {
                        entries.add(item);
                    }
                })
                .build();
        Registry.register(Registries.ITEM_GROUP, Identifier.of(TeyvatMod.MOD_ID, "blocks"), group);

        // Страховка: блоки также попадают в стандартную вкладку «Строительные блоки»
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.BUILDING_BLOCKS).register(entries -> {
            for (Block block : ALL_BLOCKS) {
                entries.add(block);
            }
        });
    }
}
