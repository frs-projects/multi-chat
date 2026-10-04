package de.taczbg.multichat.core;

import de.taczbg.multichat.core.redis.RedisStreamBus;

/**
 * Facade the loader adapter drives: start/stop the bus, publish events.
 * The most recently started instance also backs {@link de.taczbg.multichat.api.MultiChatApi}
 * for third-party mods.
 */
public final class MultiChatCore {

    private static volatile MultiChatCore active;

    private RedisStreamBus bus;
    private CoreConfig cfg;

    public synchronized void start(CoreConfig cfg, InboundHandler handler, CoreLog log) {
        stop(0);
        this.cfg = cfg;
        this.bus = new RedisStreamBus(cfg, handler, log);
        this.bus.start();
        active = this;
    }

    public synchronized void stop(long flushTimeoutMs) {
        if (bus != null) {
            bus.stop(flushTimeoutMs);
            bus = null;
        }
        if (active == this) {
            active = null;
        }
    }

    public boolean isRunning() {
        return bus != null;
    }

    public boolean isConnected() {
        RedisStreamBus b = bus;
        return b != null && b.isConnected();
    }

    public String connectionState() {
        RedisStreamBus b = bus;
        return b == null ? "stopped" : b.connectionState();
    }

    /** Publishes an event stamped with this endpoint's source id and the current time. */
    public void publish(String type, String uuid, String name, String content, String meta) {
        RedisStreamBus b = bus;
        CoreConfig c = cfg;
        if (b == null || c == null) {
            return;
        }
        b.publish(ChatMessage.now(c.serverId(), type, uuid, name, content, meta));
    }

    /** The most recently started core, or null. Used by the public API. */
    public static MultiChatCore active() {
        return active;
    }
}
