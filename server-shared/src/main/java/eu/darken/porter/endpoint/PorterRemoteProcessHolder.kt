package eu.darken.porter.endpoint

import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import eu.darken.porter.core.ServerProcess
import eu.darken.porter.server.IPorterRemoteProcess
import java.util.concurrent.TimeUnit

class PorterRemoteProcessHolder(private val process: ServerProcess) : IPorterRemoteProcess.Stub() {

    constructor(process: Process, token: IBinder?) : this(ServerProcess(process, token))

    override fun getOutputStream(): ParcelFileDescriptor = process.getOutputStream()

    override fun getInputStream(): ParcelFileDescriptor = process.getInputStream()

    override fun getErrorStream(): ParcelFileDescriptor = process.getErrorStream()

    override fun waitFor(): Int = process.waitFor()

    override fun exitValue(): Int = process.exitValue()

    override fun destroy() {
        process.destroy()
    }

    @Throws(RemoteException::class)
    override fun alive(): Boolean = process.alive()

    @Throws(RemoteException::class)
    override fun waitForTimeout(timeout: Long, unitName: String): Boolean {
        // Parsed here so an unknown name is still an IllegalArgumentException across the binder.
        return process.waitForTimeout(timeout, TimeUnit.valueOf(unitName))
    }
}
