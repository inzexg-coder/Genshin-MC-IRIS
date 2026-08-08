package net.teyvat.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.teyvat.network.QuestEventPayload;

/**
 * Клиентская часть выполнения квестов: пометка выполненным, пакет серверу
 * и золотой тост «Задание выполнено». Если висит уведомление «Новое задание»,
 * тост превращается в «Задание выполнено» на том же месте, а не добавляется снизу.
 */
public final class QuestClient {
    private QuestClient() {}

    public static void complete(String questId, String questTitle) {
        if (QuestStateClient.isCompleted(questId)) {
            return;
        }
        QuestStateClient.markCompleted(questId);
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.getNetworkHandler() != null) {
            ClientPlayNetworking.send(new QuestEventPayload(questId));
        }
        if (!QuestToast.replaceActiveNewQuest("Задание выполнено")) {
            client.getToastManager().add(new QuestToast("Задание выполнено", "«" + questTitle + "»"));
        }
    }

    /** Квест выполнен на сервере (например, победа над слаймами тренировки):
     *  помечаем локально и показываем тост. Обратный пакет серверу не шлём —
     *  сервер сам отметил выполнение. */
    public static void receiveServerCompletion(String questId, String questTitle) {
        if (QuestStateClient.isCompleted(questId)) {
            return;
        }
        QuestStateClient.markCompleted(questId);
        MinecraftClient client = MinecraftClient.getInstance();
        if (!QuestToast.replaceActiveNewQuest("Задание выполнено")) {
            client.getToastManager().add(new QuestToast("Задание выполнено", "«" + questTitle + "»"));
        }
    }
}
