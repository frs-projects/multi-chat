//? if forge {
package de.taczbg.multichat.forge.server;

import de.taczbg.multichat.forge.MultiChat;
import de.taczbg.multichat.forge.config.MultiChatConfig;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

// Broadcasts this server's online player list every forward.rosterSeconds as a
// "roster" event, so consumers know the exact player count instead of having to tally
// join/leave events (which drift after a crash or missed message). Doubles as a
// heartbeat: a consumer that stops seeing rosters can treat the server as gone.
//
// Wire format: content = comma-separated player names, meta = exact
// player count. The names are capped (MAX_NAMES) to keep stream entries small;
// meta stays exact, so a consumer can spot a capped list by comparing the two.
//
// Runs on the server thread; publish() only enqueues, so this adds no I/O to the tick.
@Mod.EventBusSubscriber(modid = MultiChat.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RosterPublisher {

    private static final int MAX_NAMES = 200;

    // Wall clock, not tick count: a lagging server should still report on schedule.
    private static long lastPublishMs;

    private RosterPublisher() {
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        long interval = MultiChatConfig.rosterSeconds() * 1000L;
        if (interval <= 0 || !MultiChat.CORE.isRunning()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastPublishMs >= interval) {
            publish(event.getServer(), now);
        }
    }

    // Publishes immediately (used right after startup/reload) and restarts the interval.
    static void publishNow(MinecraftServer server) {
        if (MultiChatConfig.rosterSeconds() > 0 && MultiChat.CORE.isRunning()) {
            publish(server, System.currentTimeMillis());
        }
    }

    private static void publish(MinecraftServer server, long now) {
        lastPublishMs = now;
        var players = server.getPlayerList().getPlayers();
        StringBuilder names = new StringBuilder();
        for (int i = 0; i < Math.min(players.size(), MAX_NAMES); i++) {
            ServerPlayer player = players.get(i);
            if (i > 0) {
                names.append(',');
            }
            names.append(player.getGameProfile().getName());
        }
        MultiChat.CORE.publish("roster", "", "", names.toString(), Integer.toString(players.size()));
    }
}
//?}
