package de.taczbg.multichat.forge.server;

import de.taczbg.multichat.core.ChatMessage;
import de.taczbg.multichat.forge.config.MultiChatConfig;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

/**
 * Prints messages from other sources into this server's chat. Always called on the
 * server thread (via {@code server.execute} from the lifecycle handoff). Own-source
 * and too-old entries are already filtered by the core bus.
 */
final class InboundPrinter {

    private InboundPrinter() {
    }

    static void print(MinecraftServer server, ChatMessage msg) {
        // Rosters are periodic telemetry for consumers like the Discord bot status;
        // printing them would spam chat every few seconds on every server.
        if ("roster".equals(msg.type())) {
            return;
        }
        if (!MultiChatConfig.displayEnabled()
                || MultiChatConfig.ignoredSources().contains(msg.source())
                || MultiChatConfig.ignoredTypes().contains(msg.type())) {
            return;
        }
        String formatted = MultiChatConfig.format(msg.type())
                .replace("{source}", msg.source())
                .replace("{name}", msg.name())
                .replace("{message}", msg.content());
        server.getPlayerList().broadcastSystemMessage(Component.literal(formatted), false);
    }
}
