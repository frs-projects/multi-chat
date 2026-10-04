//? if forge {
/*package de.taczbg.multichat.forge;

import de.taczbg.multichat.mc.MultiChatRuntime;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.AdvancementEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;

// Forge entry point: the loader's events, forwarded to MultiChatRuntime, and nothing else.
//
// Only line comments here, and in every other loader-gated file: Stonecutter comments an
// inactive file out with one block comment, which a Javadoc block inside would end early.
@Mod(MultiChatRuntime.MOD_ID)
public final class MultiChat {

    private final MultiChatRuntime runtime = new MultiChatRuntime(FMLPaths.CONFIGDIR.get());

    public MultiChat() {
        // The game event bus, not the mod bus: everything below is a running-server event.
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        runtime.onServerStarted(event.getServer());
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        runtime.onServerStopping();
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        runtime.registerCommands(event.getDispatcher());
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            runtime.onServerTick(event.getServer());
        }
    }

    @SubscribeEvent
    public void onChat(ServerChatEvent event) {
        runtime.onChat(event.getPlayer(), event.getMessage().getString());
    }

    @SubscribeEvent
    public void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            runtime.onJoin(player);
        }
    }

    @SubscribeEvent
    public void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            runtime.onLeave(player);
        }
    }

    @SubscribeEvent
    public void onDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            runtime.onDeath(player);
        }
    }

    @SubscribeEvent
    public void onAdvancement(AdvancementEvent.AdvancementEarnEvent event) {
        Advancement advancement = event.getAdvancement();
        DisplayInfo display = advancement.getDisplay();
        if (event.getEntity() instanceof ServerPlayer player && display != null && display.shouldAnnounceChat()) {
            // Mirrors PlayerAdvancements' chat broadcast, rendered server-side (en_us).
            runtime.onAdvancement(player, () -> Component.translatable(
                    "chat.type.advancement." + display.getFrame().getName(),
                    player.getDisplayName(), advancement.getChatComponent()));
        }
    }
}
*///?}
