package net.teyvat.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.block.Block;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.command.argument.BlockPosArgumentType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;
import net.teyvat.TeyvatBlocks;

/**
 * /column <x y z> [количество] — строит случайные гармоничные мраморные колонны
 * в стиле загрузочного экрана Genshin: белый мрамор + золотые пояса, случайные
 * высота, пьедестал/база, ствол и капитель. Если указано количество > 1, колонны
 * встают в ряд со случайным шагом 3-5 блоков (как колоннада на фоне).
 */
public final class ColumnCommand {
    private ColumnCommand() {}

    /** Дерево /column ... — регистрируется и самостоятельно, и как /teyvat column. */
    public static LiteralArgumentBuilder<ServerCommandSource> buildColumn() {
        return CommandManager.literal("column")
                .then(CommandManager.argument("pos", BlockPosArgumentType.blockPos())
                        .executes(ctx -> place(ctx.getSource(), BlockPosArgumentType.getLoadedBlockPos(ctx, "pos"), 1))
                        .then(CommandManager.argument("count", IntegerArgumentType.integer(1, 64))
                                .executes(ctx -> place(ctx.getSource(), BlockPosArgumentType.getLoadedBlockPos(ctx, "pos"),
                                        IntegerArgumentType.getInteger(ctx, "count")))));
    }

    private static int place(ServerCommandSource source, BlockPos pos, int count) {
        ServerWorld world = source.getWorld();
        Random random = world.getRandom();
        int placed = 0;
        for (int i = 0; i < count; i++) {
            placed += placeOne(world, pos, random);
            if (i + 1 < count) {
                Direction dir = Direction.Type.HORIZONTAL.random(random);
                pos = pos.offset(dir, 3 + random.nextInt(3)); // шаг 3..5
            }
        }
        int total = placed;
        source.sendFeedback(() -> Text.literal("§e[Teyvat] §fКолонн построено: §b" + total), false);
        return placed;
    }

    private static int placeOne(ServerWorld world, BlockPos pos, Random random) {
        if (!world.isChunkLoaded(pos) || !world.isChunkLoaded(pos.up(14))) {
            return 0;
        }
        BlockPos p = pos;
        int blocks = 0;
        boolean prevGold = false;

        // Стройная колонна (тонкий ствол) — отдельный стиль
        if (random.nextDouble() < 0.15) {
            int height = 4 + random.nextInt(4);
            for (int i = 0; i < height; i++) {
                set(world, p, TeyvatBlocks.MARBLE_COLUMN_SMALL);
                p = p.up();
                blocks++;
            }
            set(world, p, random.nextBoolean() ? TeyvatBlocks.GOLD_TRIMMED_MARBLE : TeyvatBlocks.MARBLE_COLUMN_CAPITAL);
            p = p.up();
            blocks++;
            return blocks;
        }

        // Пьедестал или база
        double base = random.nextDouble();
        if (base < 0.28) {
            set(world, p, TeyvatBlocks.MARBLE_PEDESTAL);
            p = p.up();
            blocks++;
        } else if (base < 0.55) {
            set(world, p, TeyvatBlocks.MARBLE_COLUMN_BASE);
            p = p.up();
            blocks++;
        } else if (base < 0.70) {
            set(world, p, TeyvatBlocks.GOLD_TRIMMED_MARBLE);
            p = p.up();
            blocks++;
            prevGold = true;
        }

        // Ствол: гармоничный микс (без двух золотых колец подряд)
        int height = 4 + random.nextInt(4);
        for (int i = 0; i < height; i++) {
            Block block;
            double roll = random.nextDouble();
            if (prevGold || roll < 0.42) {
                block = TeyvatBlocks.MARBLE_COLUMN_MID;
                prevGold = false;
            } else if (roll < 0.66) {
                block = TeyvatBlocks.MARBLE_COLUMN;
                prevGold = false;
            } else if (roll < 0.84) {
                block = TeyvatBlocks.MARBLE_PILLAR;
                prevGold = false;
            } else {
                block = TeyvatBlocks.GOLD_TRIMMED_MARBLE;
                prevGold = true;
            }
            set(world, p, block);
            p = p.up();
            blocks++;
        }

        // Капитель / навершие
        double cap = random.nextDouble();
        if (cap < 0.42) {
            set(world, p, TeyvatBlocks.MARBLE_COLUMN_CAPITAL);
            prevGold = false;
        } else if (cap < 0.62) {
            set(world, p, TeyvatBlocks.GOLD_TRIMMED_MARBLE);
            prevGold = true;
        } else if (cap < 0.82) {
            set(world, p, TeyvatBlocks.MARBLE_COLUMN_SMALL);
            prevGold = false;
        } else {
            set(world, p, TeyvatBlocks.POLISHED_MARBLE);
            prevGold = false;
        }
        p = p.up();
        blocks++;

        // Иногда маленький венчающий элемент
        if (random.nextDouble() < 0.25 && blocks < 12) {
            boolean goldTop = !prevGold && random.nextBoolean();
            set(world, p, goldTop ? TeyvatBlocks.GOLD_TRIMMED_MARBLE : TeyvatBlocks.MARBLE_COLUMN_SMALL);
            p = p.up();
            blocks++;
        }
        return blocks;
    }

    private static void set(ServerWorld world, BlockPos pos, Block block) {
        world.setBlockState(pos, block.getDefaultState(), 3);
    }
}
