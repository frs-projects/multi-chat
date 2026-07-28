package de.taczbg.multichat.core.redis;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RespCodecTest {

    private static Object parse(String wire) throws IOException {
        return RespCodec.readReply(new ByteArrayInputStream(wire.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void encodesCommandAsBulkStringArray() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        RespCodec.writeCommand(out, "XADD", "multichat:events", "*");
        assertEquals("*3\r\n$4\r\nXADD\r\n$16\r\nmultichat:events\r\n$1\r\n*\r\n",
                out.toString(StandardCharsets.UTF_8));
    }

    @Test
    void encodesUtf8ByteLengths() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        RespCodec.writeCommand(out, "ü");
        assertEquals("*1\r\n$2\r\nü\r\n", out.toString(StandardCharsets.UTF_8));
    }

    @Test
    void parsesSimpleString() throws IOException {
        assertEquals("PONG", parse("+PONG\r\n"));
    }

    @Test
    void parsesInteger() throws IOException {
        assertEquals(42L, parse(":42\r\n"));
    }

    @Test
    void parsesBulkStringAndNil() throws IOException {
        assertEquals("hello", parse("$5\r\nhello\r\n"));
        assertNull(parse("$-1\r\n"));
        assertNull(parse("*-1\r\n"));
    }

    @Test
    void parsesNestedArrays() throws IOException {
        // Shape of an XREADGROUP reply: [[stream, [[id, [field, value]]]]]
        String wire = "*1\r\n*2\r\n$6\r\nstream\r\n*1\r\n*2\r\n$3\r\n1-0\r\n*2\r\n$4\r\ntype\r\n$4\r\nchat\r\n";
        Object reply = parse(wire);
        List<?> streams = (List<?>) reply;
        List<?> stream = (List<?>) streams.get(0);
        assertEquals("stream", stream.get(0));
        List<?> entry = (List<?>) ((List<?>) stream.get(1)).get(0);
        assertEquals("1-0", entry.get(0));
        assertEquals(List.of("type", "chat"), entry.get(1));
    }

    @Test
    void errorReplyThrowsWithCode() {
        RespException e = assertThrows(RespException.class,
                () -> parse("-BUSYGROUP Consumer Group name already exists\r\n"));
        assertEquals("BUSYGROUP", e.code());
    }

    @Test
    void eofThrows() {
        assertThrows(EOFException.class, () -> parse(""));
        assertThrows(EOFException.class, () -> parse("$5\r\nhel"));
    }
}
