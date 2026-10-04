package de.taczbg.multichat.core;

/**
 * Callback for messages received from other sources.
 * <p>
 * Called on the Redis consumer thread — implementations MUST hand off to their own
 * scheduler (e.g. {@code server.execute(...)} on Forge) and return quickly; the entry
 * is ACKed as soon as this returns.
 */
@FunctionalInterface
public interface InboundHandler {
    void onMessage(ChatMessage msg);
}
