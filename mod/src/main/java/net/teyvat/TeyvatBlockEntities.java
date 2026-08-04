package net.teyvat;

import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

import net.teyvat.block.entity.MarbleDoorBlockEntity;

public final class TeyvatBlockEntities {
    private TeyvatBlockEntities() {}

    public static final BlockEntityType<MarbleDoorBlockEntity> MARBLE_DOOR =
            FabricBlockEntityTypeBuilder.create(MarbleDoorBlockEntity::new, TeyvatBlocks.MARBLE_DOOR).build();

    public static void register() {
        Registry.register(Registries.BLOCK_ENTITY_TYPE,
                Identifier.of(TeyvatMod.MOD_ID, "marble_door"), MARBLE_DOOR);
    }
}
