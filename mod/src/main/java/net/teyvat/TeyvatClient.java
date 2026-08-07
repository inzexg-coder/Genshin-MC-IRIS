package net.teyvat;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.teyvat.client.TravelerChoiceClient;
import net.teyvat.client.TravelerChoiceScreen;
import net.teyvat.client.CameraController;
import net.teyvat.client.ZoomController;
import net.teyvat.client.TravelerNotesScreen;
import net.teyvat.client.paimon.PaimonEntityRenderer;
import net.teyvat.client.paimon.PaimonEntity;
import net.teyvat.client.paimon.PaimonManager;
import net.teyvat.client.QuestStateClient;
import net.teyvat.network.NotesOpenPayload;
import net.teyvat.network.QuestStatePayload;
import net.teyvat.network.TravelerChoiceOpenPayload;
import net.teyvat.network.TravelerChoiceSyncPayload;
import org.lwjgl.glfw.GLFW;

public class TeyvatClient implements ClientModInitializer {
    /** Общая категория всех клавиш мода. В 1.21.10 Category.create() сам регистрирует категорию
     *  и бросает исключение при повторном вызове — поэтому создаём её один раз. */
    private static final KeyBinding.Category CATEGORY =
            KeyBinding.Category.create(Identifier.of(TeyvatMod.MOD_ID, "main"));

    public static final KeyBinding OPEN_NOTES = KeyBindingHelper.registerKeyBinding(
            new KeyBinding("key.teyvat.notes", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_N, CATEGORY));
    /** Зум по кнопке: удержание клавиши плавно приближает камеру (вместо подзорной трубы). */
    public static final KeyBinding ZOOM = KeyBindingHelper.registerKeyBinding(
            new KeyBinding("key.teyvat.zoom", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_C, CATEGORY));
    /** Свободная камера (удержание или переключатель — режим в config/teyvat.json → camera.free_look_mode). */
    public static final KeyBinding FREE_CAM = KeyBindingHelper.registerKeyBinding(
            new KeyBinding("key.teyvat.camera", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_V, CATEGORY));

    /** Тики до открытия экрана выбора (ждём, пока мир догрузится). -1 = не запрошено. */
    private static int choiceOpenDelay = -1;

    @Override
    public void onInitializeClient() {
        // Рендер клиентской Паймон.
        EntityRendererRegistry.register(PaimonEntity.TYPE, PaimonEntityRenderer::new);

        // Клавиша открывает заметки в любом режиме игры (клиентская сторона).
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            ZoomController.tick();
            CameraController.tick();
            while (OPEN_NOTES.wasPressed()) {
                if (client.currentScreen == null) {
                    client.setScreen(new TravelerNotesScreen());
                }
            }
            if (choiceOpenDelay >= 0) {
                choiceOpenDelay--;
                if (choiceOpenDelay <= 0) {
                    if (client.currentScreen == null && client.player != null && client.world != null) {
                        client.setScreen(new TravelerChoiceScreen());
                        choiceOpenDelay = -1;
                    } else {
                        choiceOpenDelay = 0;   // мир ещё грузится — пробуем в следующем тике
                    }
                }
            }
            PaimonManager.tick();
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
            context.client().execute(() -> choiceOpenDelay = 5);
        });

        // Состояние квестов с сервера: какие задания уже выполнены (не повторяем уроки).
        ClientPlayNetworking.registerGlobalReceiver(QuestStatePayload.ID, (payload, context) -> {
            context.client().execute(() ->
                    QuestStateClient.set(payload.meetPaimon(), payload.tryScroll(), payload.tryZoom()));
        });

        // Синхронизация выбора: применяем скин игрока через локальные текстуры мода.
        ClientPlayNetworking.registerGlobalReceiver(TravelerChoiceSyncPayload.ID, (payload, context) -> {
            context.client().execute(() -> {
                TravelerChoiceClient.set(payload.playerId(), payload.choice());
                if (context.client().player != null
                        && payload.playerId().equals(context.client().player.getUuid())) {
                    // Свой выбор — Паймон выходит поприветствовать путешественника.
                    PaimonManager.startIntro();
                }
            });
        });

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            if (client.player != null) {
                if (TravelerChoiceClient.get(client.player.getUuid()) != null) {
                    PaimonManager.startIntro();
                }
            }
        });
    }
}
