package net.teyvat;

import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

import net.teyvat.block.entity.MarbleTallDoorBlockEntity;

public final class TeyvatBlockEntities {
    private TeyvatBlockEntities() {}

    public static final BlockEntityType<MarbleTallDoorBlockEntity> MARBLE_TALL_DOOR =
            FabricBlockEntityTypeBuilder.create(MarbleTallDoorBlockEntity::new, TeyvatBlocks.MARBLE_DOOR).build();

    public static void register() {
        Registry.register(Registries.BLOCK_ENTITY_TYPE,
                Identifier.of(TeyvatMod.MOD_ID, "marble_tall_door"), MARBLE_TALL_DOOR);
    }
}
