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
import net.teyvat.client.TravelerChoiceClient;
import net.teyvat.client.TravelerChoiceScreen;
import net.teyvat.client.TravelerNotesScreen;
import net.teyvat.network.NotesOpenPayload;
import net.teyvat.network.TravelerChoiceOpenPayload;
import net.teyvat.network.TravelerChoiceSyncPayload;
import org.lwjgl.glfw.GLFW;

public class TeyvatClient implements ClientModInitializer {
    public static final KeyBinding OPEN_NOTES = KeyBindingHelper.registerKeyBinding(
            new KeyBinding("key.teyvat.notes", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_N,
                    KeyBinding.Category.create(Identifier.of(TeyvatMod.MOD_ID, "main"))));

    /** Тики до открытия экрана выбора (ждём, пока мир догрузится). -1 = не запрошено. */
    private static int choiceOpenDelay = -1;

    @Override
    public void onInitializeClient() {
        // Клавиша открывает заметки в любом режиме игры (клиентская сторона).
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (OPEN_NOTES.wasPressed()) {
                if (client.currentScreen == null) {
                    client.setScreen(new TravelerNotesScreen());
                }
            }
            if (choiceOpenDelay > 0) {
                choiceOpenDelay--;
                if (choiceOpenDelay == 0 && client.currentScreen == null && client.player != null && client.world != null) {
                    client.setScreen(new TravelerChoiceScreen());
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

        // Первый вход: сервер просит открыть экран выбора путешественника.
        ClientPlayNetworking.registerGlobalReceiver(TravelerChoiceOpenPayload.ID, (payload, context) -> {
            context.client().execute(() -> choiceOpenDelay = 25);
        });

        // Синхронизация выбора: применяем скин игрока через локальные текстуры мода.
        ClientPlayNetworking.registerGlobalReceiver(TravelerChoiceSyncPayload.ID, (payload, context) -> {
            context.client().execute(() -> TravelerChoiceClient.set(payload.playerId(), payload.choice()));
        });

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            if (client.player != null) {
                client.player.sendMessage(Text.literal(
                        "§e[Teyvat 0.8.45] §7Заметки путешественника: клавиша §bN§7 или §b/teyvat notes"), false);
            }
        });
    }
}
