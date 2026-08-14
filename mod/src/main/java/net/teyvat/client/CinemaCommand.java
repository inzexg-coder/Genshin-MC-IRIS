package net.teyvat.client;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.command.CommandRegistryAccess;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.text.Text;

/** Клиентские команды /cinema: кинокамера для съёмки боя со стороны (для видео). */
public final class CinemaCommand {
    private CinemaCommand() {}

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher,
                               CommandRegistryAccess registryAccess) {
        dispatcher.register(ClientCommandManager.literal("cinema")
                .then(ClientCommandManager.literal("side")
                        .executes(ctx -> startSide(ctx, 6.0, 0.0, false))
                        .then(ClientCommandManager.argument("dist", DoubleArgumentType.doubleArg(2.0, 40.0))
                                .executes(ctx -> startSide(ctx, DoubleArgumentType.getDouble(ctx, "dist"), 0.0, false))
                                .then(ClientCommandManager.argument("height", DoubleArgumentType.doubleArg(-5.0, 10.0))
                                        .executes(ctx -> startSide(ctx, DoubleArgumentType.getDouble(ctx, "dist"),
                                                DoubleArgumentType.getDouble(ctx, "height"), false))
                                        .then(ClientCommandManager.argument("side", StringArgumentType.word())
                                                .executes(ctx -> startSide(ctx, DoubleArgumentType.getDouble(ctx, "dist"),
                                                        DoubleArgumentType.getDouble(ctx, "height"),
                                                        "left".equalsIgnoreCase(StringArgumentType.getString(ctx, "side"))))))))
                .then(ClientCommandManager.literal("orbit")
                        .executes(ctx -> startOrbit(ctx, 7.0, 1.2, 0.4))
                        .then(ClientCommandManager.argument("dist", DoubleArgumentType.doubleArg(2.0, 40.0))
                                .executes(ctx -> startOrbit(ctx, DoubleArgumentType.getDouble(ctx, "dist"), 1.2, 0.4))
                                .then(ClientCommandManager.argument("height", DoubleArgumentType.doubleArg(-5.0, 10.0))
                                        .executes(ctx -> startOrbit(ctx, DoubleArgumentType.getDouble(ctx, "dist"),
                                                DoubleArgumentType.getDouble(ctx, "height"), 0.4))
                                        .then(ClientCommandManager.argument("speed", DoubleArgumentType.doubleArg(0.05, 3.0))
                                                .executes(ctx -> startOrbit(ctx, DoubleArgumentType.getDouble(ctx, "dist"),
                                                        DoubleArgumentType.getDouble(ctx, "height"),
                                                        DoubleArgumentType.getDouble(ctx, "speed")))))))
                .then(ClientCommandManager.literal("shots")
                        .executes(ctx -> startShots(ctx, 5.5, 0.4, false))
                        .then(ClientCommandManager.argument("dist", DoubleArgumentType.doubleArg(2.0, 40.0))
                                .executes(ctx -> startShots(ctx, DoubleArgumentType.getDouble(ctx, "dist"), 0.4, false))
                                .then(ClientCommandManager.argument("height", DoubleArgumentType.doubleArg(-5.0, 10.0))
                                        .executes(ctx -> startShots(ctx, DoubleArgumentType.getDouble(ctx, "dist"),
                                                DoubleArgumentType.getDouble(ctx, "height"), false))
                                        .then(ClientCommandManager.argument("side", StringArgumentType.word())
                                                .executes(ctx -> startShots(ctx, DoubleArgumentType.getDouble(ctx, "dist"),
                                                        DoubleArgumentType.getDouble(ctx, "height"),
                                                        "left".equalsIgnoreCase(StringArgumentType.getString(ctx, "side"))))))))
                .then(ClientCommandManager.literal("off").executes(CinemaCommand::off)));
    }

    private static int startSide(CommandContext<FabricClientCommandSource> ctx, double dist, double height, boolean left) {
        CinematicCamera.ensureThirdPerson();
        CinematicCamera.startSide(dist, height, left);
        ctx.getSource().sendFeedback(Text.literal("§b[Teyvat] §fКинокамера: сбоку"
                + (left ? " слева" : " справа") + " (дист. " + dist + ", высота " + height + "). /cinema off — выключить."));
        return 1;
    }

    private static int startOrbit(CommandContext<FabricClientCommandSource> ctx, double dist, double height, double speed) {
        CinematicCamera.ensureThirdPerson();
        CinematicCamera.startOrbit(dist, height, speed);
        ctx.getSource().sendFeedback(Text.literal("§b[Teyvat] §fКинокамера: орбита (дист. " + dist
                + ", высота " + height + ", скорость " + speed + " рад/с). /cinema off — выключить."));
        return 1;
    }

    private static int startShots(CommandContext<FabricClientCommandSource> ctx, double dist, double height, boolean left) {
        CinematicShots.arm(dist, height, left);
        return 1;
    }

    private static int off(CommandContext<FabricClientCommandSource> ctx) {
        CinematicCamera.stop();
        CinematicShots.disarm();
        ctx.getSource().sendFeedback(Text.literal("§b[Teyvat] §fКинокамера и съёмка ударов выключены — обычная Teyvat Camera."));
        return 1;
    }
}
