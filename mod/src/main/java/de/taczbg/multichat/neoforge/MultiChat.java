//? if neoforge {
package de.taczbg.multichat.neoforge;

import de.taczbg.multichat.mc.MultiChatRuntime;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.AdvancementEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

// NeoForge entry point: the loader's events, forwarded to MultiChatRuntime, and nothing else.
// The same runtime the Forge build uses; everything here is a spelling difference.
@Mod(MultiChatRuntime.MOD_ID)
public final class MultiChat {

    private final MultiChatRuntime runtime = new MultiChatRuntime(FMLPaths.CONFIGDIR.get());

    public MultiChat() {
        // The game event bus, not the mod bus: everything below is a running-server event.
        NeoForge.EVENT_BUS.register(this);
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
    public void onServerTick(ServerTickEvent.Post event) {
        runtime.onServerTick(event.getServer());
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
        AdvancementHolder advancement = event.getAdvancement();
        DisplayInfo display = advancement.value().display().orElse(null);
        if (event.getEntity() instanceof ServerPlayer player && display != null && display.shouldAnnounceChat()) {
            // The component PlayerAdvancements broadcasts, rendered server-side (en_us).
            runtime.onAdvancement(player, () -> display.getType().createAnnouncement(advancement, player));
        }
    }
}
//?}
