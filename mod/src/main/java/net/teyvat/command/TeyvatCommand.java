package net.teyvat.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.teyvat.network.NotesOpenPayload;

/** Корневая команда /teyvat: подкоманды column и notes. /column остаётся самостоятельной. */
public final class TeyvatCommand {
    private TeyvatCommand() {}

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher,
                                CommandRegistryAccess registryAccess,
                                CommandManager.RegistrationEnvironment environment) {
        var column = ColumnCommand.buildColumn();
        dispatcher.register(column);
        dispatcher.register(CommandManager.literal("teyvat")
                .then(column)
                .then(CommandManager.literal("notes").executes(TeyvatCommand::openNotes)));
    }

    private static int openNotes(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity player = ctx.getSource().getPlayer();
        if (player != null) {
            ServerPlayNetworking.send(player, new NotesOpenPayload());
            ctx.getSource().sendFeedback(() -> Text.literal(
                    "§e[Teyvat] §fЗаметки путешественника открыты. Клавиша по умолчанию: §bN§f (меняется в «Управление»)."),
                    false);
        }
        return 1;
    }
}
