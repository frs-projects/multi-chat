package de.taczbg.multichat.core.redis;

/**
 * A Redis "-ERR ..."-style protocol error reply. Unchecked and separate from
 * {@link java.io.IOException} so callers can distinguish "the server said no"
 * (e.g. BUSYGROUP, which is expected during group bootstrap) from a dead connection.
 */
public class RespException extends RuntimeException {

    public RespException(String message) {
        super(message);
    }

    /** First word of the error reply, e.g. "BUSYGROUP", "NOGROUP", "ERR". */
    public String code() {
        String msg = getMessage();
        if (msg == null || msg.isEmpty()) {
            return "";
        }
        int space = msg.indexOf(' ');
        return space < 0 ? msg : msg.substring(0, space);
    }
}
