package net.teyvat;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TeyvatMod implements ModInitializer {
    public static final String MOD_ID = "teyvat";
    public static final Logger LOGGER = LoggerFactory.getLogger("Teyvat");

    @Override
    public void onInitialize() {
        TeyvatBlocks.register();
        TeyvatBlocks.registerItemGroup();
        LOGGER.info("Teyvat mod initialized");
    }
}
