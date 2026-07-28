package de.taczbg.multichat.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One event on the multichat stream.
 * <p>
 * Serialized as flat Redis stream field/value pairs (not a JSON blob) so entries are
 * inspectable with {@code redis-cli XRANGE} and parseable by any consumer without a
 * JSON library. Unknown extra fields written by other producers are ignored on read.
 *
 * @param source    id of the producing endpoint (server id like "EU_1", or connector id like "discord")
 * @param type      "chat", "join", "leave", "death", "advancement", "status", or any custom type
 * @param uuid      player UUID string; empty for non-player events
 * @param name      player/display name; empty for e.g. "status"
 * @param content   plain-text payload; empty for join/leave (consumers format from type + name)
 * @param timestamp epoch millis at publish time
 * @param meta      optional free-form string for custom publishers; empty when unused
 */
public record ChatMessage(String source, String type, String uuid, String name,
                          String content, long timestamp, String meta) {

    public static ChatMessage now(String source, String type, String uuid, String name,
                                  String content, String meta) {
        return new ChatMessage(nullToEmpty(source), nullToEmpty(type), nullToEmpty(uuid),
                nullToEmpty(name), nullToEmpty(content), System.currentTimeMillis(), nullToEmpty(meta));
    }

    /** Alternating field/value strings for XADD. Empty fields are omitted (except source/type/ts). */
    public String[] toFields() {
        List<String> out = new ArrayList<>(14);
        out.add("source");
        out.add(source);
        out.add("type");
        out.add(type);
        out.add("ts");
        out.add(Long.toString(timestamp));
        if (!uuid.isEmpty()) {
            out.add("uuid");
            out.add(uuid);
        }
        if (!name.isEmpty()) {
            out.add("name");
            out.add(name);
        }
        if (!content.isEmpty()) {
            out.add("content");
            out.add(content);
        }
        if (!meta.isEmpty()) {
            out.add("meta");
            out.add(meta);
        }
        return out.toArray(new String[0]);
    }

    public static ChatMessage fromFields(Map<String, String> fields) {
        long ts;
        try {
            ts = Long.parseLong(fields.getOrDefault("ts", "0"));
        } catch (NumberFormatException e) {
            ts = 0L;
        }
        return new ChatMessage(
                fields.getOrDefault("source", ""),
                fields.getOrDefault("type", ""),
                fields.getOrDefault("uuid", ""),
                fields.getOrDefault("name", ""),
                fields.getOrDefault("content", ""),
                ts,
                fields.getOrDefault("meta", ""));
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
