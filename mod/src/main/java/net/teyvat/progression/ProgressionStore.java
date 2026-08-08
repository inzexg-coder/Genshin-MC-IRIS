package net.teyvat.progression;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.teyvat.config.TeyvatConfig;
import net.teyvat.network.ProgressionSyncPayload;

import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Прогрессия игрока: Ранг Приключений, опыт, примогемы и ростера персонажей.
 * Хранится в NBT игрока (compound "teyvat"); ростера персонажей — JSON-строкой
 * внутри того же compound. Сервер-авторитет: соло и выделенный сервер одинаковы.
 */
public final class ProgressionStore {
    public static final String KEY = "teyvat";
    private static final String AR = "ar";
    private static final String EXP = "exp";
    private static final String PRIMOGEMS = "primogems";
    private static final String CHARS = "characters";
    private static final String CHOICE_TAG_PREFIX = "teyvat:traveler_";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type ROSTER_TYPE = new TypeToken<Map<String, CharacterState>>() {}.getType();
    /** Состояние игроков в памяти: UUID → compound (заполняется при чтении NBT). */
    private static final Map<UUID, NbtCompound> PROGRESSION = new ConcurrentHashMap<>();

    private ProgressionStore() {}

    // ---- NBT-жизненный цикл (вызывают миксины) ----

    public static void onRead(ServerPlayerEntity player, ReadView view) {
        NbtCompound t = new NbtCompound();
        t.putInt(AR, view.getInt(AR, 1));
        t.putLong(EXP, view.getLong(EXP, 0L));
        t.putLong(PRIMOGEMS, view.getLong(PRIMOGEMS, 0L));
        t.putString(CHARS, view.getString(CHARS, "{}"));
        ensureDefaults(player, t);
        PROGRESSION.put(player.getUuid(), t);
    }

    public static void onWrite(ServerPlayerEntity player, WriteView view) {
        NbtCompound t = PROGRESSION.get(player.getUuid());
        if (t == null) {
            t = new NbtCompound();
            ensureDefaults(player, t);
            PROGRESSION.put(player.getUuid(), t);
        }
        view.putInt(AR, t.getInt(AR, 1));
        view.putLong(EXP, t.getLong(EXP, 0L));
        view.putLong(PRIMOGEMS, t.getLong(PRIMOGEMS, 0L));
        view.putString(CHARS, t.getString(CHARS, "{}"));
    }

    /** Смерть/ресурс-спавн: прогресс переносится на новую сущность игрока. */
    public static void onCopy(ServerPlayerEntity newPlayer, ServerPlayerEntity oldPlayer) {
        NbtCompound old = PROGRESSION.get(oldPlayer.getUuid());
        if (old != null) {
            PROGRESSION.put(newPlayer.getUuid(), old.copy());
        }
    }

    public static void onDisconnect(ServerPlayerEntity player) {
        PROGRESSION.remove(player.getUuid());
    }

    // ---- Доступ к состоянию ----

    private static NbtCompound state(ServerPlayerEntity player) {
        NbtCompound t = PROGRESSION.get(player.getUuid());
        if (t == null) {
            t = new NbtCompound();
            ensureDefaults(player, t);
            PROGRESSION.put(player.getUuid(), t);
        }
        return t;
    }

    private static void ensureDefaults(ServerPlayerEntity player, NbtCompound t) {
        if (!t.contains(AR)) {
            t.putInt(AR, 1);
        }
        if (!t.contains(EXP)) {
            t.putLong(EXP, 0L);
        }
        if (!t.contains(PRIMOGEMS)) {
            t.putLong(PRIMOGEMS, 0L);
        }
        if (!t.contains(CHARS)) {
            // Первый персонаж — выбранный путешественник (если выбор уже сделан).
            String choice = null;
            for (String tag : player.getCommandTags()) {
                if (tag.startsWith(CHOICE_TAG_PREFIX)) {
                    choice = tag.substring(CHOICE_TAG_PREFIX.length());
                }
            }
            Map<String, CharacterState> roster = new HashMap<>();
            if ("lumine".equals(choice) || "aether".equals(choice)) {
                roster.put(choice, new CharacterState());
            }
            t.putString(CHARS, GSON.toJson(roster));
        }
    }

    /** Добавить персонажа в ростера (например, выбранного путешественника), если его нет. */
    public static void seedTraveler(ServerPlayerEntity player, String charId) {
        Map<String, CharacterState> roster = getRoster(player);
        if (!roster.containsKey(charId)) {
            roster.put(charId, new CharacterState());
            setRoster(player, roster);
            sync(player);
        }
    }

    // ---- Ранг Приключений ----

    public static int getAr(ServerPlayerEntity player) {
        return state(player).getInt(AR, 1);
    }

    public static long getExp(ServerPlayerEntity player) {
        return state(player).getLong(EXP, 0L);
    }

    public static long getPrimogems(ServerPlayerEntity player) {
        return state(player).getLong(PRIMOGEMS, 0L);
    }

    public static long expToNextAr(ServerPlayerEntity player) {
        return expNeedForAr(getAr(player));
    }

    /** Опыт, нужный для перехода с данного ранга на следующий — с каждым уровнем тяжелее. */
    public static long expNeedForAr(int ar) {
        TeyvatConfig.Progression p = TeyvatConfig.get().progression;
        if (ar >= p.max_ar) {
            return 0;
        }
        return Math.max(1, Math.round(p.ar_exp_base * Math.pow(ar, p.ar_exp_power)));
    }

    /** Прибавить опыт Ранга Приключений. Возвращает true, если ранг повысился. */
    public static boolean addArExp(ServerPlayerEntity player, long amount) {
        NbtCompound t = state(player);
        int ar = t.getInt(AR, 1);
        long exp = t.getLong(EXP, 0L) + amount;
        int max = TeyvatConfig.get().progression.max_ar;
        boolean rankUp = false;
        while (ar < max && exp >= expNeedForAr(ar)) {
            exp -= expNeedForAr(ar);
            ar++;
            rankUp = true;
        }
        if (ar >= max) {
            exp = 0;
        }
        t.putInt(AR, ar);
        t.putLong(EXP, exp);
        sync(player);
        return rankUp;
    }

    public static void setAr(ServerPlayerEntity player, int ar) {
        int max = TeyvatConfig.get().progression.max_ar;
        state(player).putInt(AR, Math.max(1, Math.min(max, ar)));
        sync(player);
    }

    public static void addPrimogems(ServerPlayerEntity player, long amount) {
        state(player).putLong(PRIMOGEMS, Math.max(0L, getPrimogems(player) + amount));
        sync(player);
    }

    // ---- Ростера персонажей (JSON) ----

    public static Map<String, CharacterState> getRoster(ServerPlayerEntity player) {
        String json = state(player).getString(CHARS, "{}");
        try {
            Map<String, CharacterState> roster = GSON.fromJson(json, ROSTER_TYPE);
            return roster != null ? roster : new HashMap<>();
        } catch (Exception e) {
            return new HashMap<>();
        }
    }

    private static void setRoster(ServerPlayerEntity player, Map<String, CharacterState> roster) {
        state(player).putString(CHARS, GSON.toJson(roster));
    }

    public static CharacterState getCharacter(ServerPlayerEntity player, String charId) {
        return getRoster(player).getOrDefault(charId, new CharacterState());
    }

    public static int getCharacterLevel(ServerPlayerEntity player, String charId) {
        return getCharacter(player, charId).level;
    }

    public static void setCharacterLevel(ServerPlayerEntity player, String charId, int level) {
        int max = TeyvatConfig.get().progression.max_char_level;
        Map<String, CharacterState> roster = getRoster(player);
        CharacterState c = roster.computeIfAbsent(charId, k -> new CharacterState());
        c.level = Math.max(1, Math.min(max, level));
        setRoster(player, roster);
        sync(player);
    }

    public static void addCharacterLevel(ServerPlayerEntity player, String charId, int levels) {
        setCharacterLevel(player, charId, getCharacterLevel(player, charId) + levels);
    }

    /** Опыт персонажа: копит и повышает уровень, пока хватает опыта. */
    public static void addCharacterExp(ServerPlayerEntity player, String charId, long amount) {
        Map<String, CharacterState> roster = getRoster(player);
        CharacterState c = roster.computeIfAbsent(charId, k -> new CharacterState());
        c.exp += amount;
        int max = TeyvatConfig.get().progression.max_char_level;
        while (c.level < max && c.exp >= charExpNeed(c.level)) {
            c.exp -= charExpNeed(c.level);
            c.level++;
        }
        if (c.level >= max) {
            c.exp = 0;
        }
        setRoster(player, roster);
        sync(player);
    }

    /** Опыт, нужный для перехода на следующий уровень персонажа. */
    public static long charExpNeed(int level) {
        TeyvatConfig.Progression p = TeyvatConfig.get().progression;
        if (level >= p.max_char_level) {
            return 0;
        }
        return Math.max(1, Math.round(p.char_exp_base * Math.pow(level, p.char_exp_power)));
    }

    // ---- Синхронизация и сброс ----

    public static void sync(ServerPlayerEntity player) {
        if (player.getEntityWorld() == null || player.getEntityWorld().getServer() == null) {
            return;
        }
        ServerPlayNetworking.send(player, new ProgressionSyncPayload(
                getAr(player), getExp(player), expToNextAr(player), getPrimogems(player),
                GSON.toJson(getRoster(player))));
    }

    /** Сброс прогрессии до значений по умолчанию (для тестов и админа). */
    public static void reset(ServerPlayerEntity player) {
        NbtCompound t = new NbtCompound();
        ensureDefaults(player, t);
        PROGRESSION.put(player.getUuid(), t);
        sync(player);
    }
}
