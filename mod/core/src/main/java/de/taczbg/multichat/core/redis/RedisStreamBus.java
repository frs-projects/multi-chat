package de.taczbg.multichat.core.redis;

import de.taczbg.multichat.core.ChatMessage;
import de.taczbg.multichat.core.CoreConfig;
import de.taczbg.multichat.core.CoreLog;
import de.taczbg.multichat.core.InboundHandler;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Owns the two Redis connections and threads of the bridge:
 * <ul>
 *   <li><b>Publisher thread</b> — drains a bounded queue into XADD. {@link #publish} only
 *       enqueues, so callers (e.g. the MC server thread) never touch network I/O.</li>
 *   <li><b>Consumer thread</b> — XREADGROUP loop with BLOCK; entries from our own source
 *       or older than the catch-up cap are ACKed silently, everything else goes to the
 *       {@link InboundHandler} (still on this thread — the handler must hand off).</li>
 * </ul>
 * The two threads hold separate {@link RespClient} instances: a connection sitting in a
 * blocking XREADGROUP can never be shared with XADD/XACK traffic. Both reconnect with
 * exponential backoff (1s doubling to 30s).
 */
public final class RedisStreamBus {

    private static final int QUEUE_CAPACITY = 1000;
    private static final long BACKOFF_START_MS = 1000;
    private static final long BACKOFF_CAP_MS = 30_000;

    private final CoreConfig cfg;
    private final InboundHandler handler;
    private final CoreLog log;
    private final LinkedBlockingQueue<ChatMessage> outbox = new LinkedBlockingQueue<>(QUEUE_CAPACITY);

    private final Thread pubThread;
    private final Thread subThread;
    private volatile boolean running = true;
    private volatile boolean pubConnected;
    private volatile boolean subConnected;
    private volatile RespClient subClient;
    private boolean overflowWarned;

    public RedisStreamBus(CoreConfig cfg, InboundHandler handler, CoreLog log) {
        this.cfg = cfg;
        this.handler = handler;
        this.log = log;
        this.pubThread = new Thread(this::publisherLoop, "multichat-redis-pub");
        this.subThread = new Thread(this::consumerLoop, "multichat-redis-sub");
        pubThread.setDaemon(true);
        subThread.setDaemon(true);
    }

    public void start() {
        pubThread.start();
        subThread.start();
    }

    /** Never blocks. On overflow the oldest queued message is dropped (warned once). */
    public void publish(ChatMessage msg) {
        while (!outbox.offer(msg)) {
            outbox.poll();
            if (!overflowWarned) {
                overflowWarned = true;
                log.warn("outbound queue full (" + QUEUE_CAPACITY + ") - dropping oldest messages; is Redis reachable?");
            }
        }
    }

    public boolean isConnected() {
        return pubConnected;
    }

    public String connectionState() {
        return "publisher=" + (pubConnected ? "connected" : "disconnected")
                + " consumer=" + (subConnected ? "connected" : "disconnected")
                + " queued=" + outbox.size();
    }

    /**
     * Stops the consumer immediately, then gives the publisher up to {@code flushTimeoutMs}
     * to drain remaining messages (so a final "status: stopping" event actually gets out).
     */
    public void stop(long flushTimeoutMs) {
        running = false;
        RespClient sc = subClient;
        if (sc != null) {
            sc.close(); // breaks a pending XREADGROUP BLOCK with a SocketException
        }
        subThread.interrupt();
        try {
            subThread.join(2000);
            pubThread.join(Math.max(flushTimeoutMs, 0));
            if (pubThread.isAlive()) {
                pubThread.interrupt();
                pubThread.join(500);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---- publisher ----

    private void publisherLoop() {
        RespClient client = newClient(10_000);
        long backoff = BACKOFF_START_MS;
        try {
            // Eager first connect so status reporting is accurate before any message flows;
            // failures are fine, the per-message retry loop below reconnects.
            try {
                client.connect();
                pubConnected = true;
                log.info("publisher connected to Redis " + cfg.host() + ":" + cfg.port());
            } catch (IOException | RuntimeException e) {
                log.warn("publisher could not connect yet (" + e.getMessage() + ")");
            }
            while (running || !outbox.isEmpty()) {
                ChatMessage msg;
                try {
                    msg = outbox.poll(250, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    break;
                }
                if (msg == null) {
                    continue;
                }
                while (true) {
                    try {
                        if (!client.isConnected()) {
                            client.connect();
                            pubConnected = true;
                            backoff = BACKOFF_START_MS;
                            log.info("publisher connected to Redis " + cfg.host() + ":" + cfg.port());
                        }
                        xadd(client, msg);
                        break;
                    } catch (IOException | RespException e) {
                        pubConnected = false;
                        client.close();
                        if (!running) {
                            return; // shutting down and Redis unreachable: give up on the rest
                        }
                        log.warn("publisher lost Redis (" + e.getMessage() + "), retrying in " + backoff + "ms");
                        try {
                            Thread.sleep(backoff);
                        } catch (InterruptedException ie) {
                            return;
                        }
                        backoff = Math.min(backoff * 2, BACKOFF_CAP_MS);
                    }
                }
            }
        } finally {
            pubConnected = false;
            client.close();
        }
    }

    private void xadd(RespClient client, ChatMessage msg) throws IOException {
        String[] fields = msg.toFields();
        String[] cmd = new String[6 + fields.length];
        cmd[0] = "XADD";
        cmd[1] = cfg.streamKey();
        cmd[2] = "MAXLEN";
        cmd[3] = "~";
        cmd[4] = Long.toString(cfg.maxStreamLength());
        cmd[5] = "*";
        System.arraycopy(fields, 0, cmd, 6, fields.length);
        client.command(cmd);
    }

    // ---- consumer ----

    private void consumerLoop() {
        long backoff = BACKOFF_START_MS;
        while (running) {
            RespClient client = newClient((int) (cfg.blockMs() + 10_000));
            subClient = client;
            try {
                client.connect();
                ensureGroup(client);
                subConnected = true;
                backoff = BACKOFF_START_MS;
                log.info("consumer connected, group=" + cfg.groupName() + " stream=" + cfg.streamKey());
                drainPending(client);
                while (running) {
                    Object reply = client.command("XREADGROUP", "GROUP", cfg.groupName(), cfg.serverId(),
                            "COUNT", "32", "BLOCK", Long.toString(cfg.blockMs()),
                            "STREAMS", cfg.streamKey(), ">");
                    handleReadReply(client, reply);
                }
            } catch (IOException | RuntimeException e) {
                subConnected = false;
                client.close();
                if (!running) {
                    return;
                }
                log.warn("consumer lost Redis (" + e.getMessage() + "), retrying in " + backoff + "ms");
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException ie) {
                    return;
                }
                backoff = Math.min(backoff * 2, BACKOFF_CAP_MS);
            }
        }
        subConnected = false;
    }

    private void ensureGroup(RespClient client) throws IOException {
        try {
            client.command("XGROUP", "CREATE", cfg.streamKey(), cfg.groupName(), "$", "MKSTREAM");
        } catch (RespException e) {
            if (!"BUSYGROUP".equals(e.code())) {
                throw e;
            }
        }
    }

    /**
     * Recovers entries that were delivered but not ACKed before a crash: reading with an
     * explicit id ("0") returns this consumer's pending list and never blocks.
     */
    private void drainPending(RespClient client) throws IOException {
        while (running) {
            Object reply = client.command("XREADGROUP", "GROUP", cfg.groupName(), cfg.serverId(),
                    "COUNT", "100", "STREAMS", cfg.streamKey(), "0");
            if (countEntries(reply) == 0) {
                return;
            }
            handleReadReply(client, reply);
        }
    }

    @SuppressWarnings("unchecked")
    private static int countEntries(Object reply) {
        if (!(reply instanceof List<?> streams) || streams.isEmpty()) {
            return 0;
        }
        List<Object> stream = (List<Object>) streams.get(0);
        return stream.size() < 2 || !(stream.get(1) instanceof List<?> entries) ? 0 : entries.size();
    }

    /** Reply shape: [[streamName, [[id, [f1, v1, ...]], ...]]] or null on BLOCK timeout. */
    @SuppressWarnings("unchecked")
    private void handleReadReply(RespClient client, Object reply) throws IOException {
        if (!(reply instanceof List<?> streams)) {
            return;
        }
        for (Object streamObj : streams) {
            List<Object> stream = (List<Object>) streamObj;
            if (stream.size() < 2 || !(stream.get(1) instanceof List<?> entries)) {
                continue;
            }
            for (Object entryObj : entries) {
                List<Object> entry = (List<Object>) entryObj;
                String id = (String) entry.get(0);
                // Pending entries whose data was trimmed away come back with null fields.
                if (entry.size() >= 2 && entry.get(1) instanceof List<?> fieldValues) {
                    deliver(ChatMessage.fromFields(toMap((List<Object>) fieldValues)));
                }
                client.command("XACK", cfg.streamKey(), cfg.groupName(), id);
            }
        }
    }

    private void deliver(ChatMessage msg) {
        if (msg.source().equals(cfg.serverId())) {
            return; // our own event echoed back
        }
        if (cfg.catchupMaxAgeMs() > 0 && msg.timestamp() < System.currentTimeMillis() - cfg.catchupMaxAgeMs()) {
            return; // too old to replay into live chat
        }
        try {
            handler.onMessage(msg);
        } catch (RuntimeException e) {
            log.warn("inbound handler threw for message from " + msg.source(), e);
        }
    }

    private static Map<String, String> toMap(List<Object> fieldValues) {
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i + 1 < fieldValues.size(); i += 2) {
            map.put(String.valueOf(fieldValues.get(i)), String.valueOf(fieldValues.get(i + 1)));
        }
        return map;
    }

    private RespClient newClient(int soTimeoutMs) {
        return new RespClient(cfg.host(), cfg.port(), cfg.username(), cfg.password(), soTimeoutMs);
    }
}
