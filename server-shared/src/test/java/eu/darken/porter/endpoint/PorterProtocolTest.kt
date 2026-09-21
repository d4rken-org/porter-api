package eu.darken.porter.endpoint

import android.os.Bundle
import android.os.IBinder
import eu.darken.porter.manager.protocol.PorterManagerProtocol
import eu.darken.porter.protocol.PorterProtocol
import eu.darken.porter.server.IPorterApplication
import eu.darken.porter.server.IPorterManager
import eu.darken.porter.server.IPorterRemoteProcess
import eu.darken.porter.server.IPorterService
import eu.darken.porter.server.IPorterServiceConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import rikka.shizuku.ShizukuApiConstants
import rikka.shizuku.server.ConfigManager

/** The constants a client writes on the wire, against what the generated stubs answer. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class PorterProtocolTest {

    @Test
    @Throws(Exception::class)
    fun theDescriptorsMatchTheGeneratedStubs() {
        assertEquals(PorterProtocol.DESCRIPTOR, stub().interfaceDescriptor)
        assertEquals(PorterManagerProtocol.DESCRIPTOR, managerStub().interfaceDescriptor)
    }

    /** AIDL ids below 100, raw protocol codes 100 to 199, porsh 200 to 299, the app from 10000. */
    @Test
    fun theCodesStayInTheirRanges() {
        assertTrue(LAST_AIDL_CODE < PROTOCOL_RANGE.first)
        assertTrue(PorterProtocol.TRANSACTION_transactRemote in PROTOCOL_RANGE)
        assertTrue(PorterProtocol.TRANSACTION_PORSH_BASE in PORSH_RANGE)
        assertTrue(PorterProtocol.TRANSACTION_PORSH_BASE + 2 in PORSH_RANGE)
        assertEquals(10000, PorterProtocol.TRANSACTION_APP_BASE)
        assertTrue(PorterProtocol.TRANSACTION_APP_BASE > PORSH_RANGE.last)
    }

    @Test
    fun theFloorNeverExceedsTheVersion() {
        assertTrue(PorterProtocol.MIN_VERSION <= PorterProtocol.VERSION)
    }

    @Test
    fun theFlagValuesAreTheOnesTheConfigManagerStores() {
        assertEquals(ConfigManager.FLAG_ALLOWED, PorterManagerProtocol.FLAG_ALLOWED)
        assertEquals(ConfigManager.FLAG_DENIED, PorterManagerProtocol.FLAG_DENIED)
        assertEquals(ConfigManager.MASK_PERMISSION, PorterManagerProtocol.MASK_PERMISSION)
    }

    /** One service class serves both wires only if both ask it to shut down with the same code. */
    @Test
    fun theDestroyCodeIsTheOneTheHostAnswers() {
        assertEquals(ShizukuApiConstants.USER_SERVICE_TRANSACTION_destroy, PorterProtocol.USER_SERVICE_TRANSACTION_destroy)
    }

    private companion object {
        /** The AIDL ids occupy `FIRST_CALL_TRANSACTION + 1 .. + 12`, with 7 left unallocated. */
        const val LAST_AIDL_CODE = IBinder.FIRST_CALL_TRANSACTION + 12
        val PROTOCOL_RANGE = 100..199
        val PORSH_RANGE = 200..299

        fun stub(): IPorterService.Stub = object : IPorterService.Stub() {
            override fun attach(application: IPorterApplication?, args: Bundle?): Bundle? = null

            override fun getUid(): Int = 0

            override fun checkPermission(permission: String?): Int = 0

            override fun getSELinuxContext(): String? = null

            override fun getSystemProperty(name: String?, defaultValue: String?): String? = null

            override fun setSystemProperty(name: String?, value: String?) {
            }

            override fun addUserService(conn: IPorterServiceConnection?, args: Bundle?): Int = 0

            override fun removeUserService(conn: IPorterServiceConnection?, args: Bundle?): Int = 0

            override fun requestPermission(requestCode: Int) {
            }

            override fun checkSelfPermission(): Boolean = false

            override fun shouldShowRequestPermissionRationale(): Boolean = false
        }

        fun managerStub(): IPorterManager.Stub = object : IPorterManager.Stub() {
            override fun newProcess(cmd: Array<String>?, env: Array<String>?, dir: String?): IPorterRemoteProcess? = null

            override fun exit() {
            }

            override fun attachUserService(binder: IBinder?, token: String?) {
            }

            override fun dispatchPermissionConfirmationResult(uid: Int, pid: Int, requestCode: Int, allowed: Boolean, onetime: Boolean) {
            }

            override fun getFlagsForUid(uid: Int, mask: Int): Int = 0

            override fun updateFlagsForUid(uid: Int, mask: Int, value: Int) {
            }
        }
    }
}
