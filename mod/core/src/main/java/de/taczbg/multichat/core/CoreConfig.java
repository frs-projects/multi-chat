package de.taczbg.multichat.core;

/**
 * Connection + behavior settings for the Redis stream bus, loader-independent.
 * The loader adapter builds this from its own config file.
 *
 * @param host             Redis host
 * @param port             Redis port
 * @param tls              connect with TLS, verifying the certificate and host name
 * @param username         optional ACL username; empty for legacy single-password AUTH
 * @param password         optional password; empty disables AUTH
 * @param streamKey        stream key, e.g. "multichat:events"
 * @param serverId         this endpoint's source id; also used for consumer group/name
 * @param maxStreamLength  approximate MAXLEN trim applied on every XADD
 * @param catchupMaxAgeMs  entries older than this are ACKed without being displayed
 * @param blockMs          XREADGROUP BLOCK duration; socket timeout is set well above this
 */
public record CoreConfig(String host, int port, boolean tls, String username, String password,
                         String streamKey, String serverId, long maxStreamLength,
                         long catchupMaxAgeMs, long blockMs) {

    /** Consumer group name for this endpoint ("mc:" prefix distinguishes servers from bridge connectors). */
    public String groupName() {
        return "mc:" + serverId;
    }
}
