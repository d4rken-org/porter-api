package rikka.shizuku.server.util

import android.os.SELinux
import android.system.Os

object OsUtils {

    val uid: Int = Os.getuid()
    val pid: Int = Os.getpid()
    val seLinuxContext: String? = try {
        SELinux.getContext()
    } catch (tr: Throwable) {
        null
    }
}
