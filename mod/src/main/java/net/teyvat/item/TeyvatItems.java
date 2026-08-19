package net.teyvat.item;

import net.minecraft.item.Item;
import net.minecraft.item.ToolMaterial;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.teyvat.TeyvatMod;

import java.util.List;

/**
 * Предметы Тейвата: слизь слайма (3 тира), мора, яйцо призыва гидро слайма.
 * Тир дропа зависит от уровня слайма: слизь — 1+ ур,
 * выделения — 40+ ур, концентрат — 60+ ур.
 */
public final class TeyvatItems {
    public static final Item SLIME_CONDENSATE = register("slime_condensate");
    public static final Item SLIME_SECRETIONS = register("slime_secretions");
    public static final Item SLIME_CONCENTRATE = register("slime_concentrate");
    public static final Item MORA = register("mora");
    /** Тупой меч (Dull Blade) — стартовый меч Путешественника, ★☆☆☆☆, Base ATK=23. */
    public static final Item DULL_BLADE = registerDullBlade();

    /** Яйцо призыва гидро слайма для тестов и творческого режима. */
    public static final Item HYDRO_SLIME_SPAWN_EGG = registerSpawnEgg();

    public static final List<Item> ALL = List.of(
            SLIME_CONDENSATE, SLIME_SECRETIONS, SLIME_CONCENTRATE, MORA,
            DULL_BLADE, HYDRO_SLIME_SPAWN_EGG);

    private TeyvatItems() {}

    private static Item register(String name) {
        Identifier id = Identifier.of(TeyvatMod.MOD_ID, name);
        return Registry.register(Registries.ITEM, id,
                new Item(new Item.Settings().registryKey(RegistryKey.of(RegistryKeys.ITEM, id))));
    }

    /** Dull Blade (Тупой меч): ★☆☆☆☆, Base ATK=23, стартовый меч. */
    private static Item registerDullBlade() {
        Identifier id = Identifier.of(TeyvatMod.MOD_ID, "dull_blade");
        Item.Settings settings = ToolMaterial.WOOD.applySwordSettings(
                new Item.Settings(), 23.0f, -2.0f);
        return Registry.register(Registries.ITEM, id, new Item(settings.registryKey(RegistryKey.of(RegistryKeys.ITEM, id))));
    }

    /** Раскраска яйца уже в текстуре, без двойного тинта. */
    private static Item registerSpawnEgg() {
        Identifier id = Identifier.of(TeyvatMod.MOD_ID, "hydro_slime_spawn_egg");
        return Registry.register(Registries.ITEM, id,
                new HydroSlimeSpawnEggItem(
                        new Item.Settings().registryKey(RegistryKey.of(RegistryKeys.ITEM, id))));
    }

    public static void register() {
        // статические поля выше регистрируют всё; вызов нужен только для явного порядка
    }
}
