package de.taczbg.multichat.mc;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.logging.LogUtils;
import de.taczbg.multichat.core.CoreLog;
import de.taczbg.multichat.core.MultiChatCore;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameRules;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.function.Supplier;

/**
 * Everything multichat does in a running server, for every loader. Each loader's mod class
 * forwards its events here and does nothing else.
 * <p>
 * Threading contract: every {@code on…} method runs on the server thread. Inbound messages
 * arrive on the Redis consumer thread and are handed to the server thread via
 * {@link MinecraftServer#execute}; outbound publishing only enqueues (see RedisStreamBus),
 * so event handlers never touch network I/O. All handlers are pure observers: nothing is
 * cancelled or modified.
 */
public final class MultiChatRuntime {

    /** Filled in from {@code mod.id} by Stonecutter. */
    public static final String MOD_ID = /*$ mod_id*/ "multichat";

    private static final Logger LOGGER = LogUtils.getLogger();

    // Roster wire format: content = comma-separated player names, meta = exact player count.
    // The names are capped to keep stream entries small; meta stays exact, so a consumer can
    // spot a capped list by comparing the two.
    private static final int MAX_ROSTER_NAMES = 200;

    private static final CoreLog CORE_LOG = new CoreLog() {
        @Override
        public void info(String msg) {
            LOGGER.info("[redis] {}", msg);
        }

        @Override
        public void warn(String msg, Throwable t) {
            if (t == null) {
                LOGGER.warn("[redis] {}", msg);
            } else {
                LOGGER.warn("[redis] {}", msg, t);
            }
        }
    };

    private final MultiChatCore core = new MultiChatCore();
    private final Path configDir;

    // Wall clock, not tick count: a lagging server should still report on schedule.
    private long lastRosterMs;

    public MultiChatRuntime(Path configDir) {
        this.configDir = configDir;
    }

    // ---- lifecycle ----

    public void onServerStarted(MinecraftServer server) {
        MultiChatConfig.load(configDir);
        startCore(server);
    }

    public void onServerStopping() {
        if (core.isRunning()) {
            if (MultiChatConfig.forwardStatus()) {
                core.publish("status", "", "", "stopping", "");
            }
            // Bounded flush so the "stopping" notice gets out without stalling shutdown.
            core.stop(3000);
        }
    }

    // Starts (or restarts) the bus from the currently loaded config; dormant without a server id.
    private void startCore(MinecraftServer server) {
        core.stop(1000);
        if (MultiChatConfig.serverId().isEmpty()) {
            LOGGER.warn("server.id is empty in multichat-common.toml - multichat stays dormant");
            return;
        }
        core.start(MultiChatConfig.toCoreConfig(),
                msg -> server.execute(() -> InboundPrinter.print(server, msg)),
                CORE_LOG);
        if (MultiChatConfig.forwardStatus()) {
            core.publish("status", "", "", "started", "");
        }
        // Right after a reload the world may already be populated - don't make consumers
        // wait a full interval for the first roster.
        if (MultiChatConfig.rosterSeconds() > 0) {
            publishRoster(server, System.currentTimeMillis());
        }
    }

    // ---- event taps ----

    public void onChat(ServerPlayer player, String message) {
        if (core.isRunning() && MultiChatConfig.forwardChat()) {
            publish("chat", player, message);
        }
    }

    public void onJoin(ServerPlayer player) {
        if (core.isRunning() && MultiChatConfig.forwardJoinLeave()) {
            publish("join", player, "");
        }
    }

    public void onLeave(ServerPlayer player) {
        if (core.isRunning() && MultiChatConfig.forwardJoinLeave()) {
            publish("leave", player, "");
        }
    }

    public void onDeath(ServerPlayer player) {
        if (!core.isRunning() || !MultiChatConfig.forwardDeaths()
                || !player.level().getGameRules().getBoolean(GameRules.RULE_SHOWDEATHMESSAGES)) {
            return;
        }
        // Same component vanilla is about to broadcast; rendered server-side (en_us).
        publish("death", player, player.getCombatTracker().getDeathMessage().getString());
    }

    /**
     * An advancement that announces itself in chat. The loader builds {@code announcement}
     * because the advancement API differs between versions; it is only called for once the
     * cheap checks pass.
     */
    public void onAdvancement(ServerPlayer player, Supplier<Component> announcement) {
        if (!core.isRunning() || !MultiChatConfig.forwardAdvancements()
                || !player.level().getGameRules().getBoolean(GameRules.RULE_ANNOUNCE_ADVANCEMENTS)) {
            return;
        }
        publish("advancement", player, announcement.get().getString());
    }

    // Broadcasts the online player list every forward.rosterSeconds as a "roster" event, so
    // consumers know the exact player count instead of having to tally join/leave events
    // (which drift after a crash or missed message). Doubles as a heartbeat.
    public void onServerTick(MinecraftServer server) {
        long interval = MultiChatConfig.rosterSeconds() * 1000L;
        if (interval <= 0 || !core.isRunning()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastRosterMs >= interval) {
            publishRoster(server, now);
        }
    }

    private void publishRoster(MinecraftServer server, long now) {
        lastRosterMs = now;
        var players = server.getPlayerList().getPlayers();
        StringBuilder names = new StringBuilder();
        for (int i = 0; i < Math.min(players.size(), MAX_ROSTER_NAMES); i++) {
            if (i > 0) {
                names.append(',');
            }
            names.append(players.get(i).getGameProfile().getName());
        }
        core.publish("roster", "", "", names.toString(), Integer.toString(players.size()));
    }

    private void publish(String type, ServerPlayer player, String content) {
        core.publish(type, player.getUUID().toString(), player.getGameProfile().getName(), content, "");
    }

    // ---- commands ----

    public void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("multichat")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("reload").executes(ctx -> {
                    MultiChatConfig.load(configDir);
                    startCore(ctx.getSource().getServer());
                    ctx.getSource().sendSuccess(() -> Component.literal(
                            "Multichat reloaded (" + (core.isRunning() ? "running as '"
                                    + MultiChatConfig.serverId() + "'" : "dormant") + ")."), true);
                    return Command.SINGLE_SUCCESS;
                }))
                .then(Commands.literal("status").executes(ctx -> {
                    ctx.getSource().sendSuccess(() -> Component.literal(
                            "Multichat: " + core.connectionState()), false);
                    return Command.SINGLE_SUCCESS;
                })));
    }
}
