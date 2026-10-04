package de.taczbg.multichat.mc;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.mojang.logging.LogUtils;
import de.taczbg.multichat.core.CoreConfig;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Loads {@code config/multichat-common.toml}. Same hand-rolled NightConfig approach as
 * servertransfer's TransferConfig: read directly (NightConfig is on the classpath via
 * Forge and NeoForge), default template written on first run, reloadable via "/multichat reload".
 * All fields are only written on the server thread (load/reload) and read as snapshots.
 */
public final class MultiChatConfig {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String FILE_NAME = "multichat-common.toml";
    private static final long BLOCK_MS = 5000;

    private static final Map<String, String> DEFAULT_FORMATS = Map.of(
            "chat", "§8[§b{source}§8]§r <{name}> {message}",
            "join", "§8[§b{source}§8]§e {name} joined the game",
            "leave", "§8[§b{source}§8]§e {name} left the game",
            "death", "§8[§b{source}§8]§7 {message}",
            "advancement", "§8[§b{source}§8]§a {message}",
            "status", "§8[§b{source}§8]§6 Server {message}",
            "default", "§8[§b{source}§8]§r {name}: {message}");

    private static String host = "127.0.0.1";
    private static int port = 6379;
    private static boolean tls = false;
    private static String username = "";
    private static String password = "";
    private static String streamKey = "multichat:events";
    private static long maxStreamLength = 10000;
    private static String serverId = "";
    private static boolean forwardChat = true;
    private static boolean forwardJoinLeave = true;
    private static boolean forwardDeaths = true;
    private static boolean forwardAdvancements = true;
    private static boolean forwardStatus = true;
    private static long rosterSeconds = 30;
    private static boolean displayEnabled = true;
    private static long maxCatchupAgeSeconds = 300;
    private static Set<String> ignoredSources = Set.of();
    private static Set<String> ignoredTypes = Set.of();
    private static Map<String, String> formats = DEFAULT_FORMATS;

    private MultiChatConfig() {
    }

    /** This server's source id ({@code server.id}); empty = mod stays dormant. */
    public static String serverId() {
        return serverId;
    }

    public static boolean forwardChat() {
        return forwardChat;
    }

    public static boolean forwardJoinLeave() {
        return forwardJoinLeave;
    }

    public static boolean forwardDeaths() {
        return forwardDeaths;
    }

    public static boolean forwardAdvancements() {
        return forwardAdvancements;
    }

    public static boolean forwardStatus() {
        return forwardStatus;
    }

    /** Interval between periodic roster (online player list) broadcasts; 0 disables them. */
    public static long rosterSeconds() {
        return rosterSeconds;
    }

    public static boolean displayEnabled() {
        return displayEnabled;
    }

    public static Set<String> ignoredSources() {
        return ignoredSources;
    }

    public static Set<String> ignoredTypes() {
        return ignoredTypes;
    }

    /** Format template for a message type, falling back to the "default" template. */
    public static String format(String type) {
        return formats.getOrDefault(type, formats.get("default"));
    }

    public static CoreConfig toCoreConfig() {
        return new CoreConfig(host, port, tls, username, password, streamKey, serverId,
                maxStreamLength, maxCatchupAgeSeconds * 1000, BLOCK_MS);
    }

    /** Loads (creating a default file if missing) from {@code configDir/multichat-common.toml}. */
    public static void load(Path configDir) {
        Path path = configDir.resolve(FILE_NAME);
        if (!Files.exists(path)) {
            writeDefaultTemplate(path);
        }
        try (CommentedFileConfig fileConfig = CommentedFileConfig.builder(path).sync().build()) {
            fileConfig.load();

            host = fileConfig.getOrElse("redis.host", "127.0.0.1");
            port = fileConfig.<Number>getOrElse("redis.port", 6379).intValue();
            tls = fileConfig.getOrElse("redis.tls", false);
            username = fileConfig.getOrElse("redis.username", "");
            password = fileConfig.getOrElse("redis.password", "");
            streamKey = fileConfig.getOrElse("redis.streamKey", "multichat:events");
            maxStreamLength = fileConfig.<Number>getOrElse("redis.maxStreamLength", 10000).longValue();

            serverId = fileConfig.getOrElse("server.id", "").trim();

            forwardChat = fileConfig.getOrElse("forward.chat", true);
            forwardJoinLeave = fileConfig.getOrElse("forward.joinLeave", true);
            forwardDeaths = fileConfig.getOrElse("forward.deaths", true);
            forwardAdvancements = fileConfig.getOrElse("forward.advancements", true);
            forwardStatus = fileConfig.getOrElse("forward.status", true);
            rosterSeconds = Math.max(0, fileConfig.<Number>getOrElse("forward.rosterSeconds", 30).longValue());

            displayEnabled = fileConfig.getOrElse("display.enabled", true);
            maxCatchupAgeSeconds = fileConfig.<Number>getOrElse("display.maxCatchupAgeSeconds", 300).longValue();
            ignoredSources = Set.copyOf(fileConfig.<List<String>>getOrElse("display.ignoredSources", List.of()));
            ignoredTypes = Set.copyOf(fileConfig.<List<String>>getOrElse("display.ignoredTypes", List.of()));

            Map<String, String> loadedFormats = new LinkedHashMap<>(DEFAULT_FORMATS);
            for (String key : DEFAULT_FORMATS.keySet()) {
                loadedFormats.put(key, fileConfig.getOrElse("display.formats." + key, DEFAULT_FORMATS.get(key)));
            }
            formats = Map.copyOf(loadedFormats);
        } catch (Exception e) {
            LOGGER.error("Failed to load {} - multichat will stay dormant until this is fixed", path, e);
            serverId = "";
            return;
        }
        LOGGER.info("Loaded {} (serverId: {}, redis: {}:{}, stream: {})", path,
                serverId.isEmpty() ? "<unset - multichat dormant>" : serverId, host, port, streamKey);
    }

    private static void writeDefaultTemplate(Path path) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, DEFAULT_TEMPLATE, StandardCharsets.UTF_8);
            LOGGER.info("Created default config at {}", path);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create default " + path, e);
        }
    }

    private static final String DEFAULT_TEMPLATE = """
            # Multi-Chat configuration.
            #
            # This file is server-only. Run "/multichat reload" (requires op) to apply changes
            # without restarting; "/multichat status" shows the Redis connection state.
            #
            # server.id is this server's label on the shared stream. It MUST be unique across
            # the network (two servers sharing an id silently drop each other's messages) and
            # should match the servertransfer markers.serverId if you use that mod.
            # Leave it empty to disable multichat entirely.

            [server]
            id = ""

            [redis]
            host = "127.0.0.1"
            port = 6379
            tls = false                  # true for a TLS-enabled Redis (certificate + host name are verified)
            # username = ""              # optional ACL user; leave out for password-only AUTH
            password = ""
            streamKey = "multichat:events"
            maxStreamLength = 10000      # approximate cap, trimmed on every write

            # What THIS server publishes onto the stream.
            [forward]
            chat = true
            joinLeave = true
            deaths = true
            advancements = true
            status = true                # server started/stopping notices
            # Periodic "roster" event listing the online players, so consumers (e.g. the
            # Discord bot status) know the exact player count instead of tallying
            # join/leave events. Seconds between broadcasts; 0 disables them.
            rosterSeconds = 30

            # What THIS server prints into its own chat.
            [display]
            enabled = true
            maxCatchupAgeSeconds = 300   # skip missed messages older than this after downtime
            ignoredSources = []          # e.g. ["discord"]
            ignoredTypes = []            # e.g. ["advancement", "death"]

            # Placeholders: {source} {name} {message}. Vanilla color codes with § work.
            # Messages of unknown/custom types (posted by other mods via the API) use "default".
            [display.formats]
            chat        = "§8[§b{source}§8]§r <{name}> {message}"
            join        = "§8[§b{source}§8]§e {name} joined the game"
            leave       = "§8[§b{source}§8]§e {name} left the game"
            death       = "§8[§b{source}§8]§7 {message}"
            advancement = "§8[§b{source}§8]§a {message}"
            status      = "§8[§b{source}§8]§6 Server {message}"
            default     = "§8[§b{source}§8]§r {name}: {message}"
            """;
}
