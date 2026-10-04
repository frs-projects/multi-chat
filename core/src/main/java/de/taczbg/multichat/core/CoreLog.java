package de.taczbg.multichat.core;

/**
 * Tiny logging seam so core stays dependency-free (no slf4j on the classpath here).
 * The loader adapter delegates to its own logger.
 */
public interface CoreLog {

    void info(String msg);

    void warn(String msg, Throwable t);

    default void warn(String msg) {
        warn(msg, null);
    }

    CoreLog NONE = new CoreLog() {
        @Override
        public void info(String msg) {
        }

        @Override
        public void warn(String msg, Throwable t) {
        }
    };
}
