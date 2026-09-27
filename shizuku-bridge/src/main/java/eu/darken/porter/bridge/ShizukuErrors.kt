package eu.darken.porter.bridge

import android.os.RemoteException
import eu.darken.porter.sdk.PorterRemoteException
import eu.darken.porter.sdk.PorterSecurityException
import eu.darken.porter.sdk.extras.PorterShellException
import java.io.IOException

/** Upstream's callers catch the platform exceptions a remote Shizuku server would have raised. */
internal inline fun <T> asShizuku(call: () -> T): T = try {
    call()
} catch (e: PorterSecurityException) {
    throw e.cause as? SecurityException ?: SecurityException(e.message)
} catch (e: PorterRemoteException) {
    throw when (val cause = e.cause) {
        is RemoteException -> cause
        // The server's own failure, which reaches a Shizuku caller as itself.
        is RuntimeException -> cause
        else -> RemoteException(e.message)
    }
} catch (e: PorterShellException) {
    throw when (val cause = e.cause) {
        is RemoteException -> cause
        is RuntimeException -> cause
        else -> IllegalStateException(e.message, e)
    }
} catch (e: IOException) {
    throw IllegalStateException(e.message, e)
}
