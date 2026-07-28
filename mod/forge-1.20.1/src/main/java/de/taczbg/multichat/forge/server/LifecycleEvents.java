package de.taczbg.multichat.forge.server;

import com.mojang.brigadier.Command;
import com.mojang.logging.LogUtils;
import de.taczbg.multichat.core.CoreLog;
import de.taczbg.multichat.forge.MultiChat;
import de.taczbg.multichat.forge.config.MultiChatConfig;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

/**
 * Ties the Redis bus to the server lifecycle. Threading contract: inbound messages arrive
 * on the Redis consumer thread and are handed to the server thread via
 * {@link MinecraftServer#execute}; outbound publishing only enqueues (see RedisStreamBus),
 * so event handlers never touch network I/O.
 */
@Mod.EventBusSubscriber(modid = MultiChat.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class LifecycleEvents {

    private static final Logger LOGGER = LogUtils.getLogger();

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

    private LifecycleEvents() {
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        MultiChatConfig.load(FMLPaths.CONFIGDIR.get());
        startCore(event.getServer());
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        if (MultiChat.CORE.isRunning()) {
            if (MultiChatConfig.forwardStatus()) {
                MultiChat.CORE.publish("status", "", "", "stopping", "");
            }
            // Bounded flush so the "stopping" notice gets out without stalling shutdown.
            MultiChat.CORE.stop(3000);
        }
    }

    /** Starts (or restarts) the bus from the currently loaded config; dormant without a server id. */
    private static void startCore(MinecraftServer server) {
        MultiChat.CORE.stop(1000);
        if (MultiChatConfig.serverId().isEmpty()) {
            LOGGER.warn("server.id is empty in multichat-common.toml - multichat stays dormant");
            return;
        }
        MultiChat.CORE.start(MultiChatConfig.toCoreConfig(),
                msg -> server.execute(() -> InboundPrinter.print(server, msg)),
                CORE_LOG);
        if (MultiChatConfig.forwardStatus()) {
            MultiChat.CORE.publish("status", "", "", "started", "");
        }
        // Right after a reload the world may already be populated - don't make consumers
        // wait a full interval for the first roster.
        RosterPublisher.publishNow(server);
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("multichat")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("reload").executes(ctx -> {
                    MultiChatConfig.load(FMLPaths.CONFIGDIR.get());
                    startCore(ctx.getSource().getServer());
                    ctx.getSource().sendSuccess(() -> Component.literal(
                            "Multichat reloaded (" + (MultiChat.CORE.isRunning() ? "running as '"
                                    + MultiChatConfig.serverId() + "'" : "dormant") + ")."), true);
                    return Command.SINGLE_SUCCESS;
                }))
                .then(Commands.literal("status").executes(ctx -> {
                    ctx.getSource().sendSuccess(() -> Component.literal(
                            "Multichat: " + MultiChat.CORE.connectionState()), false);
                    return Command.SINGLE_SUCCESS;
                })));
    }
}
