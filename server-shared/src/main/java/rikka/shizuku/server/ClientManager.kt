package rikka.shizuku.server

import android.os.IBinder
import android.os.RemoteException
import eu.darken.porter.core.CallerIdentity
import eu.darken.porter.core.ClientCallback
import java.util.Collections
import moe.shizuku.server.IShizukuApplication
import rikka.shizuku.server.legacy.LegacyClientCallback
import rikka.shizuku.server.util.Logger

open class ClientManager<ConfigMgr : ConfigManager>(val configManager: ConfigMgr) {

    private val clientRecords: MutableList<ClientRecord> = Collections.synchronizedList(ArrayList())

    fun findClients(uid: Int): List<ClientRecord> = synchronized(this) {
        clientRecords.filter { it.uid == uid }
    }

    fun findClient(uid: Int, pid: Int): ClientRecord? =
        clientRecords.firstOrNull { it.pid == pid && it.uid == uid }

    fun requireClient(callingUid: Int, callingPid: Int, requiresPermission: Boolean = false): ClientRecord {
        val clientRecord = findClient(callingUid, callingPid)
        if (clientRecord == null) {
            LOGGER.w("Caller (uid %d, pid %d) is not an attached client", callingUid, callingPid)
            throw IllegalStateException("Not an attached client")
        }
        if (requiresPermission && !clientRecord.allowed) {
            throw SecurityException("Caller has no permission")
        }
        return clientRecord
    }

    fun addClient(uid: Int, pid: Int, client: IShizukuApplication, packageName: String, apiVersion: Int): ClientRecord? =
        attach(CallerIdentity(uid, pid), LegacyClientCallback(client), packageName, apiVersion)

    open fun attach(identity: CallerIdentity, callback: ClientCallback, packageName: String, apiVersion: Int): ClientRecord? {
        val clientRecord = ClientRecord(identity, callback, packageName, apiVersion)

        val entry = configManager.find(identity.uid)
        if (entry != null && entry.isAllowed()) {
            clientRecord.allowed = true
        }

        val binder = callback.asBinder()
        val deathRecipient = IBinder.DeathRecipient {
            clientRecords.remove(clientRecord)
            onClientDied(clientRecord)
        }
        try {
            binder.linkToDeath(deathRecipient, 0)
        } catch (e: RemoteException) {
            LOGGER.w(e, "addClient: linkToDeath failed")
            return null
        }

        clientRecords.add(clientRecord)
        return clientRecord
    }

    /** Runs on a binder thread, after [record]'s process died and the record was dropped. */
    protected open fun onClientDied(record: ClientRecord) {
    }

    companion object {
        protected val LOGGER = Logger("UserServiceRecord")
    }
}
