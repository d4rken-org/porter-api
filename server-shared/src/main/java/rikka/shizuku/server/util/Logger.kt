package rikka.shizuku.server.util

import android.os.SystemClock
import android.util.Log
import java.io.IOException
import java.util.Locale
import java.util.logging.FileHandler
import java.util.logging.SimpleFormatter

class Logger(private val tag: String, file: String? = null) {

    private val logger: java.util.logging.Logger? = file?.let {
        java.util.logging.Logger.getLogger(tag).also { logger ->
            try {
                val fh = FileHandler(file)
                fh.formatter = SimpleFormatter()
                logger.addHandler(fh)
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
    }

    fun isLoggable(tag: String, level: Int): Boolean = level > Log.DEBUG || debugEnabled()

    fun v(msg: String) {
        if (isLoggable(tag, Log.VERBOSE)) {
            println(Log.VERBOSE, msg)
        }
    }

    fun v(fmt: String, vararg args: Any?) {
        if (isLoggable(tag, Log.VERBOSE)) {
            println(Log.VERBOSE, String.format(Locale.ENGLISH, fmt, *args))
        }
    }

    fun v(msg: String, tr: Throwable) {
        if (isLoggable(tag, Log.VERBOSE)) {
            println(Log.VERBOSE, msg + '\n' + Log.getStackTraceString(tr))
        }
    }

    fun d(msg: String) {
        if (isLoggable(tag, Log.DEBUG)) {
            println(Log.DEBUG, msg)
        }
    }

    fun d(fmt: String, vararg args: Any?) {
        if (isLoggable(tag, Log.DEBUG)) {
            println(Log.DEBUG, String.format(Locale.ENGLISH, fmt, *args))
        }
    }

    fun d(msg: String, tr: Throwable) {
        if (isLoggable(tag, Log.DEBUG)) {
            println(Log.DEBUG, msg + '\n' + Log.getStackTraceString(tr))
        }
    }

    fun i(msg: String) {
        if (isLoggable(tag, Log.INFO)) {
            println(Log.INFO, msg)
        }
    }

    fun i(fmt: String, vararg args: Any?) {
        if (isLoggable(tag, Log.INFO)) {
            println(Log.INFO, String.format(Locale.ENGLISH, fmt, *args))
        }
    }

    fun i(msg: String, tr: Throwable) {
        if (isLoggable(tag, Log.INFO)) {
            println(Log.INFO, msg + '\n' + Log.getStackTraceString(tr))
        }
    }

    fun w(msg: String) {
        if (isLoggable(tag, Log.WARN)) {
            println(Log.WARN, msg)
        }
    }

    fun w(fmt: String, vararg args: Any?) {
        if (isLoggable(tag, Log.WARN)) {
            println(Log.WARN, String.format(Locale.ENGLISH, fmt, *args))
        }
    }

    fun w(tr: Throwable?, fmt: String, vararg args: Any?) {
        if (isLoggable(tag, Log.WARN)) {
            println(Log.WARN, String.format(Locale.ENGLISH, fmt, *args) + '\n' + Log.getStackTraceString(tr))
        }
    }

    fun w(msg: String, tr: Throwable) {
        if (isLoggable(tag, Log.WARN)) {
            println(Log.WARN, msg + '\n' + Log.getStackTraceString(tr))
        }
    }

    fun e(msg: String) {
        if (isLoggable(tag, Log.ERROR)) {
            println(Log.ERROR, msg)
        }
    }

    fun e(fmt: String, vararg args: Any?) {
        if (isLoggable(tag, Log.ERROR)) {
            println(Log.ERROR, String.format(Locale.ENGLISH, fmt, *args))
        }
    }

    fun e(msg: String, tr: Throwable) {
        if (isLoggable(tag, Log.ERROR)) {
            println(Log.ERROR, msg + '\n' + Log.getStackTraceString(tr))
        }
    }

    fun e(tr: Throwable, fmt: String, vararg args: Any?) {
        if (isLoggable(tag, Log.ERROR)) {
            println(Log.ERROR, String.format(Locale.ENGLISH, fmt, *args) + '\n' + Log.getStackTraceString(tr))
        }
    }

    fun println(priority: Int, msg: String): Int {
        logger?.info(msg)
        return Log.println(priority, tag, msg)
    }

    companion object {

        /**
         * Deadline on [SystemClock.elapsedRealtime] past which DEBUG and VERBOSE stop being
         * logged. Read on every gated call so a deadline that is never cleared still expires on its
         * own: the manager that turned it on may die without ever asking for it to be turned off.
         */
        @Volatile
        private var debugUntil = 0L

        /** @param until an [SystemClock.elapsedRealtime] deadline; anything past disables. */
        fun setDebugUntil(until: Long) {
            debugUntil = until
        }

        /**
         * Whether DEBUG and VERBOSE are logged regardless of [debugUntil]. Defaults to true, so
         * a server that never calls [setDebugAlways] logs exactly as it always did; opting into
         * the deadline is something a server does deliberately.
         *
         * Kept separate from [debugUntil] because that value belongs to whoever is currently
         * recording and is rewritten every time a lease is taken or dropped.
         */
        @Volatile
        private var debugAlways = true

        fun setDebugAlways(always: Boolean) {
            debugAlways = always
        }

        /**
         * Guard the call site with this wherever building the arguments costs something. Java evaluates
         * arguments before the call, so [isLoggable] alone cannot stop that work.
         */
        fun debugEnabled(): Boolean = debugAlways || SystemClock.elapsedRealtime() < debugUntil
    }
}
