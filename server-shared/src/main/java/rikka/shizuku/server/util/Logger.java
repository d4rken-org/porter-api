package rikka.shizuku.server.util;

import android.os.SystemClock;
import android.util.Log;

import java.io.IOException;
import java.util.Locale;
import java.util.logging.FileHandler;
import java.util.logging.SimpleFormatter;

public class Logger {

    private final String tag;
    private final java.util.logging.Logger LOGGER;

    public Logger(String tag) {
        this.tag = tag;
        this.LOGGER = null;
    }

    public Logger(String tag, String file) {
        this.tag = tag;
        this.LOGGER = java.util.logging.Logger.getLogger(tag);
        try {
            FileHandler fh = new FileHandler(file);
            fh.setFormatter(new SimpleFormatter());
            LOGGER.addHandler(fh);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Deadline on {@link SystemClock#elapsedRealtime()} past which DEBUG and VERBOSE stop being
     * logged. Read on every gated call so a deadline that is never cleared still expires on its
     * own: the manager that turned it on may die without ever asking for it to be turned off.
     */
    private static volatile long debugUntil = 0L;

    /** @param until an {@link SystemClock#elapsedRealtime()} deadline; anything past disables. */
    public static void setDebugUntil(long until) {
        debugUntil = until;
    }

    /**
     * Whether DEBUG and VERBOSE are logged regardless of {@link #debugUntil}. Defaults to true, so
     * a server that never calls {@link #setDebugAlways} logs exactly as it always did; opting into
     * the deadline is something a server does deliberately.
     *
     * <p>Kept separate from {@link #debugUntil} because that value belongs to whoever is currently
     * recording and is rewritten every time a lease is taken or dropped.
     */
    private static volatile boolean debugAlways = true;

    public static void setDebugAlways(boolean always) {
        debugAlways = always;
    }

    /**
     * Guard the call site with this wherever building the arguments costs something. Java evaluates
     * arguments before the call, so {@link #isLoggable} alone cannot stop that work.
     */
    public static boolean debugEnabled() {
        return debugAlways || SystemClock.elapsedRealtime() < debugUntil;
    }

    public boolean isLoggable(String tag, int level) {
        return level > Log.DEBUG || debugEnabled();
    }

    public void v(String msg) {
        if (isLoggable(tag, Log.VERBOSE)) {
            println(Log.VERBOSE, msg);
        }
    }

    public void v(String fmt, Object... args) {
        if (isLoggable(tag, Log.VERBOSE)) {
            println(Log.VERBOSE, String.format(Locale.ENGLISH, fmt, args));
        }
    }

    public void v(String msg, Throwable tr) {
        if (isLoggable(tag, Log.VERBOSE)) {
            println(Log.VERBOSE, msg + '\n' + Log.getStackTraceString(tr));
        }
    }

    public void d(String msg) {
        if (isLoggable(tag, Log.DEBUG)) {
            println(Log.DEBUG, msg);
        }
    }

    public void d(String fmt, Object... args) {
        if (isLoggable(tag, Log.DEBUG)) {
            println(Log.DEBUG, String.format(Locale.ENGLISH, fmt, args));
        }
    }

    public void d(String msg, Throwable tr) {
        if (isLoggable(tag, Log.DEBUG)) {
            println(Log.DEBUG, msg + '\n' + Log.getStackTraceString(tr));
        }
    }

    public void i(String msg) {
        if (isLoggable(tag, Log.INFO)) {
            println(Log.INFO, msg);
        }
    }

    public void i(String fmt, Object... args) {
        if (isLoggable(tag, Log.INFO)) {
            println(Log.INFO, String.format(Locale.ENGLISH, fmt, args));
        }
    }

    public void i(String msg, Throwable tr) {
        if (isLoggable(tag, Log.INFO)) {
            println(Log.INFO, msg + '\n' + Log.getStackTraceString(tr));
        }
    }

    public void w(String msg) {
        if (isLoggable(tag, Log.WARN)) {
            println(Log.WARN, msg);
        }
    }

    public void w(String fmt, Object... args) {
        if (isLoggable(tag, Log.WARN)) {
            println(Log.WARN, String.format(Locale.ENGLISH, fmt, args));
        }
    }

    public void w(Throwable tr, String fmt, Object... args) {
        if (isLoggable(tag, Log.WARN)) {
            println(Log.WARN, String.format(Locale.ENGLISH, fmt, args) + '\n' + Log.getStackTraceString(tr));
        }
    }

    public void w(String msg, Throwable tr) {
        if (isLoggable(tag, Log.WARN)) {
            println(Log.WARN, msg + '\n' + Log.getStackTraceString(tr));
        }
    }

    public void e(String msg) {
        if (isLoggable(tag, Log.ERROR)) {
            println(Log.ERROR, msg);
        }
    }

    public void e(String fmt, Object... args) {
        if (isLoggable(tag, Log.ERROR)) {
            println(Log.ERROR, String.format(Locale.ENGLISH, fmt, args));
        }
    }

    public void e(String msg, Throwable tr) {
        if (isLoggable(tag, Log.ERROR)) {
            println(Log.ERROR, msg + '\n' + Log.getStackTraceString(tr));
        }
    }

    public void e(Throwable tr, String fmt, Object... args) {
        if (isLoggable(tag, Log.ERROR)) {
            println(Log.ERROR, String.format(Locale.ENGLISH, fmt, args) + '\n' + Log.getStackTraceString(tr));
        }
    }

    public int println(int priority, String msg) {
        if (LOGGER != null) {
            LOGGER.info(msg);
        }
        return Log.println(priority, tag, msg);
    }
}
