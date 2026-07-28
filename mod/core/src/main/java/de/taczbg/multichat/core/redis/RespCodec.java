package de.taczbg.multichat.core.redis;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * RESP2 wire encoding/decoding, kept free of socket concerns so it is unit-testable
 * against in-memory streams. RESP2 is sufficient: we never send HELLO, so the server
 * replies in RESP2 even on Redis 6+.
 */
public final class RespCodec {

    private RespCodec() {
    }

    /** Encodes a command as a RESP array of bulk strings: {@code *N\r\n$len\r\narg\r\n...}. */
    public static void writeCommand(OutputStream out, String... args) throws IOException {
        out.write(('*' + Integer.toString(args.length) + "\r\n").getBytes(StandardCharsets.US_ASCII));
        for (String arg : args) {
            byte[] bytes = arg.getBytes(StandardCharsets.UTF_8);
            out.write(('$' + Integer.toString(bytes.length) + "\r\n").getBytes(StandardCharsets.US_ASCII));
            out.write(bytes);
            out.write('\r');
            out.write('\n');
        }
    }

    /**
     * Reads one reply. Returns String (simple/bulk), Long (integer), List&lt;Object&gt; (array),
     * or null (nil bulk/array). Error replies throw {@link RespException}.
     */
    public static Object readReply(InputStream in) throws IOException {
        int type = in.read();
        if (type < 0) {
            throw new EOFException("connection closed by Redis");
        }
        switch (type) {
            case '+':
                return readLine(in);
            case '-':
                throw new RespException(readLine(in));
            case ':':
                return Long.parseLong(readLine(in));
            case '$': {
                int len = Integer.parseInt(readLine(in));
                if (len < 0) {
                    return null;
                }
                byte[] data = readExactly(in, len);
                skipCrlf(in);
                return new String(data, StandardCharsets.UTF_8);
            }
            case '*': {
                int count = Integer.parseInt(readLine(in));
                if (count < 0) {
                    return null;
                }
                List<Object> items = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    items.add(readReply(in));
                }
                return items;
            }
            default:
                throw new IOException("unexpected RESP type byte: " + (char) type);
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(32);
        while (true) {
            int b = in.read();
            if (b < 0) {
                throw new EOFException("connection closed mid-line");
            }
            if (b == '\r') {
                int lf = in.read();
                if (lf != '\n') {
                    throw new IOException("malformed RESP line terminator");
                }
                return buf.toString(StandardCharsets.UTF_8);
            }
            buf.write(b);
        }
    }

    private static byte[] readExactly(InputStream in, int len) throws IOException {
        byte[] data = new byte[len];
        int off = 0;
        while (off < len) {
            int n = in.read(data, off, len - off);
            if (n < 0) {
                throw new EOFException("connection closed mid-bulk");
            }
            off += n;
        }
        return data;
    }

    private static void skipCrlf(InputStream in) throws IOException {
        if (in.read() != '\r' || in.read() != '\n') {
            throw new IOException("malformed RESP bulk terminator");
        }
    }
}
