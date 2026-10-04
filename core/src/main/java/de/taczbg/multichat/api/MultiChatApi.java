package de.taczbg.multichat.api;

import de.taczbg.multichat.core.MultiChatCore;

import java.util.UUID;

/**
 * Public API for other mods: publish custom message types onto the multichat stream.
 * <p>
 * Usage (only call when multichat is present, e.g. behind {@code ModList.get().isLoaded("multichat")}):
 * <pre>{@code
 * MultiChatApi.publish("bounty", player.getUUID(), player.getName().getString(),
 *                      "claimed the bounty on Steve");
 * }</pre>
 * The {@code source} field is always stamped by this mod from its configured server id —
 * callers cannot spoof another source. Whether/how a type is displayed elsewhere is decided
 * by each consumer's config ({@code display.ignoredTypes}, format templates, bridge routing).
 * <p>
 * All methods are safe to call from any thread and are no-ops while the bridge is not running.
 */
public final class MultiChatApi {

    private MultiChatApi() {
    }

    /**
     * @param type    custom message type, e.g. "bounty"; avoid the built-in types unless
     *                you intend the message to be formatted like them
     * @param player  originating player, may be null for non-player events
     * @param name    display name, may be null
     * @param content plain-text message content
     */
    public static void publish(String type, UUID player, String name, String content) {
        publish(type, player, name, content, null);
    }

    /** @param meta optional free-form string passed through to consumers (e.g. JSON of your own) */
    public static void publish(String type, UUID player, String name, String content, String meta) {
        MultiChatCore core = MultiChatCore.active();
        if (core != null) {
            core.publish(type, player == null ? "" : player.toString(), name, content, meta);
        }
    }

    /** True when the mod is running and the Redis publisher connection is up. */
    public static boolean isConnected() {
        MultiChatCore core = MultiChatCore.active();
        return core != null && core.isConnected();
    }
}
