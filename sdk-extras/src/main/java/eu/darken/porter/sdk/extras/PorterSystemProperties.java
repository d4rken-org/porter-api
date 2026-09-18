package eu.darken.porter.sdk.extras;

import eu.darken.porter.sdk.Porter;

/**
 * Typed reads and writes of the system properties, as the Porter server sees them.
 *
 * <p>Every method here reaches the server, so every one of them throws
 * {@link IllegalStateException} while no binder has been received.
 */
public final class PorterSystemProperties {

    private PorterSystemProperties() {
    }

    public static String get(String key) {
        return Porter.getSystemProperty(key, null);
    }

    public static String get(String key, String def) {
        return Porter.getSystemProperty(key, def);
    }

    public static int getInt(String key, int def) {
        return Integer.decode(Porter.getSystemProperty(key, Integer.toString(def)));
    }

    public static long getLong(String key, long def) {
        return Long.decode(Porter.getSystemProperty(key, Long.toString(def)));
    }

    public static boolean getBoolean(String key, boolean def) {
        return Boolean.parseBoolean(Porter.getSystemProperty(key, Boolean.toString(def)));
    }

    public static void set(String key, String val) {
        Porter.setSystemProperty(key, val);
    }
}
