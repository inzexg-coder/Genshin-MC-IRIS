package net.teyvat.client;

import net.teyvat.wiki.TeyvatWiki;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Состояние «Энциклопедии путешественника» на клиенте: какие записи открыты.
 *  Полный список приходит с сервера при входе, новые открытия — тихими
 *  пакетами во время игры. Локальные открытия (уроки Паймон) добавляются
 *  сразу, не дожидаясь ответа сервера. */
public final class WikiStateClient {
    private static final Set<String> discovered = new HashSet<>();

    private WikiStateClient() {}

    /** Полная синхронизация с сервером (при входе в мир). */
    public static void set(List<String> ids) {
        discovered.clear();
        discovered.addAll(ids);
    }

    /** Молча добавить запись (локальное открытие или пакет с сервера). */
    public static void discoverLocal(String entryId) {
        if (TeyvatWiki.isKnown(entryId)) {
            discovered.add(entryId);
        }
    }

    public static boolean isDiscovered(String entryId) {
        return discovered.contains(entryId);
    }

    /** Открытые записи в порядке каталога (разделы идут друг за другом). */
    public static List<TeyvatWiki.Entry> visibleEntries() {
        List<TeyvatWiki.Entry> out = new ArrayList<>();
        for (TeyvatWiki.Entry e : TeyvatWiki.entries()) {
            if (discovered.contains(e.id())) {
                out.add(e);
            }
        }
        return out;
    }
}
