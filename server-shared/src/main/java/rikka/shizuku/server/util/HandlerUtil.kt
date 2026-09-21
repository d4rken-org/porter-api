package rikka.shizuku.server.util

import android.os.Handler

object HandlerUtil {

    private var handler: Handler? = null

    var mainHandler: Handler
        get() = (handler ?: throw NullPointerException("Please call setMainHandler first"))
        set(value) {
            handler = value
        }
}
