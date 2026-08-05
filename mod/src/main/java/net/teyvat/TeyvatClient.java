package net.teyvat;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.teyvat.client.TravelerNotesScreen;
import net.teyvat.network.NotesOpenPayload;
import org.lwjgl.glfw.GLFW;

public class TeyvatClient implements ClientModInitializer {
    public static final KeyBinding OPEN_NOTES = KeyBindingHelper.registerKeyBinding(
            new KeyBinding("key.teyvat.notes", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_N,
                    KeyBinding.Category.create(Identifier.of(TeyvatMod.MOD_ID, "main"))));

    @Override
    public void onInitializeClient() {
        // Клавиша открывает заметки в любом режиме игры (клиентская сторона).
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (OPEN_NOTES.wasPressed()) {
                if (client.currentScreen == null) {
                    client.setScreen(new TravelerNotesScreen());
                }
            }
        });

        // /teyvat notes: сервер просит клиент открыть экран.
        ClientPlayNetworking.registerGlobalReceiver(NotesOpenPayload.ID, (payload, context) -> {
            context.client().execute(() -> {
                if (context.client().player != null && context.client().currentScreen == null) {
                    context.client().setScreen(new TravelerNotesScreen());
                }
            });
        });

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            if (client.player != null) {
                client.player.sendMessage(Text.literal(
                        "§e[Teyvat 0.8.12] §7Заметки путешественника: клавиша §bN§7 или §b/teyvat notes"), false);
            }
        });
    }
}
