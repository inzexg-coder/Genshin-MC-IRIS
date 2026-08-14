package net.teyvat.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.command.EntitySelector;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.teyvat.network.NotesOpenPayload;
import net.teyvat.network.TravelerChoiceOpenPayload;
import net.teyvat.progression.ProgressionStore;
import net.teyvat.mixin.common.ItemEntityMixin;
import net.teyvat.mixin.common.PlayerEntityPickupMixin;
import net.teyvat.server.PickupSelfTest;

/** Корневая команда /teyvat: column, notes, choose и прогрессия (ar/char/reset). */
public final class TeyvatCommand {
    private TeyvatCommand() {}

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher,
                                CommandRegistryAccess registryAccess,
                                CommandManager.RegistrationEnvironment environment) {
        var column = ColumnCommand.buildColumn();
        dispatcher.register(column);
        dispatcher.register(CommandManager.literal("teyvat")
                .then(column)
                .then(CommandManager.literal("notes")
                        .requires(src -> src.hasPermissionLevel(2))
                        .executes(TeyvatCommand::openNotes))
                .then(CommandManager.literal("choose").executes(TeyvatCommand::openChoice))
                .then(CommandManager.literal("pickup")
                        .requires(src -> src.hasPermissionLevel(2))
                        .executes(TeyvatCommand::pickupDebug))
                .then(CommandManager.literal("selftest")
                        .executes(TeyvatCommand::selfTest))
                .then(progression()));
    }

    /** /teyvat selftest — бросить камень у ног и проверить, поднимется ли он сам. */
    private static int selfTest(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        PickupSelfTest.start(ctx.getSource().getPlayerOrThrow());
        return 1;
    }

    /** Диагностика автоподбора: /teyvat pickup — версия мода и счётчики
     *  заблокированных ванильных попыток подбора (миксины работают, если
     *  счётчики растут при подходе к лежащим предметам). */
    private static int pickupDebug(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
        String version;
        try {
            version = FabricLoader.getInstance().getModContainer("teyvat")
                    .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?");
        } catch (Exception ignored) {
            // версия — только для диагностики
            version = "?";
        }
        final String ver = version;
        final long blocked = ItemEntityMixin.blockedCount();
        final long guarded = PlayerEntityPickupMixin.guardCount();
        ctx.getSource().sendFeedback(() -> Text.literal(
                "§e[Teyvat] §fВерсия мода: §b" + ver
                        + "§f. Автоподбор: заблокировано попыток §b" + blocked
                        + "§f (ItemEntity) и §b" + guarded
                        + "§f (PlayerEntity). Пройдись по выпавшим предметам и повтори команду — "
                        + (blocked > 0
                                ? "счётчики растут, значит миксины активны."
                                : "счётчики нулевые: игра запущена со старым jar, обнови мод.")), false);
        return 1;
    }

    /** Новый аргумент-игрок (свежий билдер каждый раз — переиспользовать нельзя). */
    private static RequiredArgumentBuilder<ServerCommandSource, EntitySelector> playerArg() {
        return CommandManager.argument("player", EntityArgumentType.player());
    }

    /** Подкоманды прогрессии: /teyvat progression get|reset, /teyvat ar, /teyvat char. */
    private static LiteralArgumentBuilder<ServerCommandSource> progression() {
        return CommandManager.literal("progression")
                .then(CommandManager.literal("get")
                        .executes(TeyvatCommand::arGetSelf)
                        .then(playerArg().executes(TeyvatCommand::arGetArg)))
                .then(CommandManager.literal("reset")
                        .then(playerArg().executes(TeyvatCommand::progressionReset)))
                .then(CommandManager.literal("ar")
                        .then(CommandManager.literal("get")
                                .executes(TeyvatCommand::arGetSelf)
                                .then(playerArg().executes(TeyvatCommand::arGetArg)))
                        .then(CommandManager.literal("set")
                                .then(playerArg()
                                        .then(CommandManager.argument("value", IntegerArgumentType.integer(1))
                                                .executes(TeyvatCommand::arSet))))
                        .then(CommandManager.literal("add")
                                .then(playerArg()
                                        .then(CommandManager.argument("value", IntegerArgumentType.integer(1))
                                                .executes(TeyvatCommand::arAdd)))))
                .then(CommandManager.literal("char")
                        .then(CommandManager.literal("set")
                                .then(playerArg()
                                        .then(CommandManager.argument("char", StringArgumentType.word())
                                                .then(CommandManager.argument("level", IntegerArgumentType.integer(1))
                                                        .executes(TeyvatCommand::charSet)))))
                        .then(CommandManager.literal("add")
                                .then(playerArg()
                                        .then(CommandManager.argument("char", StringArgumentType.word())
                                                .then(CommandManager.argument("levels", IntegerArgumentType.integer(1))
                                                        .executes(TeyvatCommand::charAdd))))));
    }

    private static int arGetSelf(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        return arGet(ctx, ctx.getSource().getPlayerOrThrow());
    }

    private static int arGetArg(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        return arGet(ctx, EntityArgumentType.getPlayer(ctx, "player"));
    }

    private static int progressionReset(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity p = EntityArgumentType.getPlayer(ctx, "player");
        ProgressionStore.reset(p);
        ctx.getSource().sendFeedback(() -> Text.literal(
                "§e[Teyvat] §fПрогрессия игрока §b" + p.getDisplayName().getString() + "§f сброшена."), false);
        return 1;
    }

    private static int arSet(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity p = EntityArgumentType.getPlayer(ctx, "player");
        ProgressionStore.setAr(p, IntegerArgumentType.getInteger(ctx, "value"));
        ctx.getSource().sendFeedback(() -> Text.literal(
                "§e[Teyvat] §fРанг §b" + p.getDisplayName().getString() + "§f: §e" + ProgressionStore.getAr(p)), false);
        return 1;
    }

    private static int arAdd(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity p = EntityArgumentType.getPlayer(ctx, "player");
        boolean rankUp = ProgressionStore.addArExp(p, IntegerArgumentType.getInteger(ctx, "value"));
        ctx.getSource().sendFeedback(() -> Text.literal(
                "§e[Teyvat] §fОпыт добавлен §b" + p.getDisplayName().getString()
                        + (rankUp ? "§f — ранг повышен до §e" + ProgressionStore.getAr(p) : "§f.")), false);
        return 1;
    }

    private static int charSet(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity p = EntityArgumentType.getPlayer(ctx, "player");
        String c = StringArgumentType.getString(ctx, "char");
        ProgressionStore.setCharacterLevel(p, c, IntegerArgumentType.getInteger(ctx, "level"));
        ctx.getSource().sendFeedback(() -> Text.literal(
                "§e[Teyvat] §f" + c + " §b" + p.getDisplayName().getString()
                        + "§f: уровень §e" + ProgressionStore.getCharacterLevel(p, c)), false);
        return 1;
    }

    private static int charAdd(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity p = EntityArgumentType.getPlayer(ctx, "player");
        String c = StringArgumentType.getString(ctx, "char");
        ProgressionStore.addCharacterLevel(p, c, IntegerArgumentType.getInteger(ctx, "levels"));
        ctx.getSource().sendFeedback(() -> Text.literal(
                "§e[Teyvat] §f" + c + " §b" + p.getDisplayName().getString()
                        + "§f: уровень §e" + ProgressionStore.getCharacterLevel(p, c)), false);
        return 1;
    }

    /** Показать прогрессию игрока: ранг, опыт до следующего, примогемы, персонажи. */
    private static int arGet(CommandContext<ServerCommandSource> ctx, ServerPlayerEntity player) {
        ctx.getSource().sendFeedback(() -> Text.literal(
                "§e[Teyvat] §b" + player.getDisplayName().getString() + "§f — ранг §e"
                        + ProgressionStore.getAr(player) + "§f, опыт §b" + ProgressionStore.getExp(player)
                        + "§f/" + ProgressionStore.expToNextAr(player)
                        + "§f, примогемы: §b" + ProgressionStore.getPrimogems(player)
                        + "§f. Персонажи: §e" + ProgressionStore.getRoster(player).keySet()), false);
        return 1;
    }

    /** Повторно открывает экран выбора путешественника (для тех, кто закрыл его в первый вход). */
    private static int openChoice(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity player = ctx.getSource().getPlayer();
        if (player != null) {
            ServerPlayNetworking.send(player, new TravelerChoiceOpenPayload());
            ctx.getSource().sendFeedback(() -> Text.literal(
                    "§e[Teyvat] §fЭкран выбора путешественника открыт."), false);
        }
        return 1;
    }

    private static int openNotes(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity player = ctx.getSource().getPlayer();
        if (player != null) {
            ServerPlayNetworking.send(player, new NotesOpenPayload());
            ctx.getSource().sendFeedback(() -> Text.literal(
                    "§e[Teyvat] §f«О сборке» открыта. Shift+N — снова открыть (только админ)."),
                    false);
        }
        return 1;
    }
}
