package de.taczbg.multichat.forge;

import com.mojang.logging.LogUtils;
import de.taczbg.multichat.core.MultiChatCore;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

@Mod(MultiChat.MODID)
public class MultiChat {

    public static final String MODID = "multichat";
    private static final Logger LOGGER = LogUtils.getLogger();

    /** Started/stopped by {@link de.taczbg.multichat.forge.server.LifecycleEvents} with the server lifecycle. */
    public static final MultiChatCore CORE = new MultiChatCore();

    public MultiChat() {
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::commonSetup);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        LOGGER.info("MultiChat mod initialized");
    }
}
