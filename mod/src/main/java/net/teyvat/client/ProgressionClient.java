package net.teyvat.client;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.teyvat.progression.CharacterState;

import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;

/** Прогрессия на клиенте: приходит с сервера, хранится для HUD и меню. */
public final class ProgressionClient {
    private static final Gson GSON = new Gson();
    private static final Type ROSTER_TYPE = new TypeToken<Map<String, CharacterState>>() {}.getType();

    private static int ar = 1;
    private static long exp;
    private static long expToNext;
    private static long primogems;
    private static Map<String, CharacterState> roster = new HashMap<>();

    private ProgressionClient() {}

    public static void set(int newAr, long newExp, long newExpToNext, long newPrimogems, String rosterJson) {
        ar = newAr;
        exp = newExp;
        expToNext = newExpToNext;
        primogems = newPrimogems;
        try {
            Map<String, CharacterState> parsed = GSON.fromJson(rosterJson, ROSTER_TYPE);
            roster = parsed != null ? parsed : new HashMap<>();
        } catch (Exception e) {
            roster = new HashMap<>();
        }
    }

    public static int getAr() {
        return ar;
    }

    public static long getExp() {
        return exp;
    }

    public static long getExpToNext() {
        return expToNext;
    }

    public static long getPrimogems() {
        return primogems;
    }

    public static Map<String, CharacterState> getRoster() {
        return roster;
    }

    public static int getCharacterLevel(String charId) {
        CharacterState c = roster.get(charId);
        return c != null ? c.level : 1;
    }
}
