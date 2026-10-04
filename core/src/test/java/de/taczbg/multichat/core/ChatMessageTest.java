package de.taczbg.multichat.core;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChatMessageTest {

    @Test
    void fieldsRoundTrip() {
        ChatMessage msg = new ChatMessage("EU_1", "chat", "uuid-1", "Steve", "hello", 1234L, "extra");
        String[] fields = msg.toFields();
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i + 1 < fields.length; i += 2) {
            map.put(fields[i], fields[i + 1]);
        }
        assertEquals(msg, ChatMessage.fromFields(map));
    }

    @Test
    void emptyOptionalFieldsAreOmitted() {
        ChatMessage msg = new ChatMessage("EU_1", "join", "", "Steve", "", 1234L, "");
        String[] fields = msg.toFields();
        assertEquals(8, fields.length); // source, type, ts, name only
    }

    @Test
    void missingFieldsParseToDefaults() {
        ChatMessage msg = ChatMessage.fromFields(Map.of("source", "discord", "type", "chat"));
        assertEquals("", msg.uuid());
        assertEquals("", msg.content());
        assertEquals(0L, msg.timestamp());
    }

    @Test
    void nowStampsNullsToEmpty() {
        ChatMessage msg = ChatMessage.now("EU_1", "status", null, null, "started", null);
        assertEquals("", msg.uuid());
        assertEquals("", msg.meta());
    }
}
