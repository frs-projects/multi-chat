package de.taczbg.multichat.core.redis;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Minimal blocking Redis client over one TCP socket — just enough for stream pub/sub
 * (AUTH, PING, XADD, XGROUP, XREADGROUP, XACK). Deliberately hand-rolled instead of
 * bundling Jedis/Lettuce: zero dependencies means no shading/jarJar and trivial reuse
 * from future loader variants.
 * <p>
 * NOT thread-safe by design — each owning thread creates its own instance. In particular
 * a connection sitting in a blocking XREADGROUP must never be shared with XADD/XACK
 * traffic from another thread.
 */
public final class RespClient implements Closeable {

    private static final int CONNECT_TIMEOUT_MS = 5000;

    private final String host;
    private final int port;
    private final String username;
    private final String password;
    private final int soTimeoutMs;

    private Socket socket;
    private InputStream in;
    private OutputStream out;

    /**
     * @param soTimeoutMs socket read timeout; must be comfortably above the longest
     *                    XREADGROUP BLOCK the caller intends to use, so a dead server
     *                    surfaces as an exception instead of hanging forever.
     */
    public RespClient(String host, int port, String username, String password, int soTimeoutMs) {
        this.host = host;
        this.port = port;
        this.username = username == null ? "" : username;
        this.password = password == null ? "" : password;
        this.soTimeoutMs = soTimeoutMs;
    }

    public void connect() throws IOException {
        close();
        socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
        socket.setSoTimeout(soTimeoutMs);
        socket.setTcpNoDelay(true);
        in = new BufferedInputStream(socket.getInputStream());
        out = new BufferedOutputStream(socket.getOutputStream());
        if (!password.isEmpty()) {
            if (username.isEmpty()) {
                command("AUTH", password);
            } else {
                command("AUTH", username, password);
            }
        }
        Object pong = command("PING");
        if (!"PONG".equals(pong)) {
            throw new IOException("unexpected PING reply: " + pong);
        }
    }

    public boolean isConnected() {
        return socket != null && socket.isConnected() && !socket.isClosed();
    }

    /** Sends one command and reads one reply. See {@link RespCodec#readReply} for reply types. */
    public Object command(String... args) throws IOException {
        if (out == null) {
            throw new IOException("not connected");
        }
        RespCodec.writeCommand(out, args);
        out.flush();
        return RespCodec.readReply(in);
    }

    /**
     * Closes the socket. Safe to call from another thread to break a blocking read
     * (the reader gets a SocketException) — this is how the bus interrupts a
     * consumer stuck in XREADGROUP BLOCK during shutdown.
     */
    @Override
    public void close() {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
            socket = null;
            in = null;
            out = null;
        }
    }
}
