package eu.darken.porter.endpoint

import android.os.Bundle
import android.os.IBinder
import eu.darken.porter.protocol.PorterProtocol
import eu.darken.porter.server.IPorterApplication
import eu.darken.porter.server.IPorterRemoteProcess
import eu.darken.porter.server.IPorterService
import eu.darken.porter.server.IPorterServiceConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import rikka.shizuku.server.ConfigManager

/** The constants a client writes on the wire, against what the generated stub answers. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class PorterProtocolTest {

    @Test
    @Throws(Exception::class)
    fun theDescriptorMatchesTheGeneratedStub() {
        assertEquals(PorterProtocol.DESCRIPTOR, stub().interfaceDescriptor)
    }

    @Test
    fun theRawCodesCannotCollideWithAnAidlId() {
        assertOutsideTheAidlRange(PorterProtocol.TRANSACTION_transactRemote)
        assertOutsideTheAidlRange(PorterProtocol.TRANSACTION_PORSH_BASE)
        assertOutsideTheAidlRange(PorterProtocol.TRANSACTION_PORSH_BASE + 1)
        assertOutsideTheAidlRange(PorterProtocol.TRANSACTION_PORSH_BASE + 2)
    }

    @Test
    fun theApplicationRangeIsAboveEverythingThisProtocolAllocates() {
        assertOutsideTheAidlRange(PorterProtocol.TRANSACTION_APP_BASE)
        assertTrue(PorterProtocol.TRANSACTION_APP_BASE > LAST_AIDL_CODE)
        assertTrue(PorterProtocol.TRANSACTION_APP_BASE > PorterProtocol.TRANSACTION_transactRemote)
        assertTrue(PorterProtocol.TRANSACTION_APP_BASE > PorterProtocol.TRANSACTION_PORSH_BASE + 2)
    }

    @Test
    fun theFlagValuesAreTheOnesTheConfigManagerStores() {
        assertEquals(ConfigManager.FLAG_ALLOWED, PorterProtocol.FLAG_ALLOWED)
        assertEquals(ConfigManager.FLAG_DENIED, PorterProtocol.FLAG_DENIED)
        assertEquals(ConfigManager.MASK_PERMISSION, PorterProtocol.MASK_PERMISSION)
    }

    private companion object {
        /** The AIDL ids occupy `FIRST_CALL_TRANSACTION + 1 .. + 17`. */
        const val LAST_AIDL_CODE = IBinder.FIRST_CALL_TRANSACTION + 17

        fun stub(): IPorterService.Stub = object : IPorterService.Stub() {
            override fun attach(application: IPorterApplication?, args: Bundle?): Bundle? = null

            override fun getUid(): Int = 0

            override fun checkPermission(permission: String?): Int = 0

            override fun getSELinuxContext(): String? = null

            override fun getSystemProperty(name: String?, defaultValue: String?): String? = null

            override fun setSystemProperty(name: String?, value: String?) {
            }

            override fun newProcess(cmd: Array<String>?, env: Array<String>?, dir: String?): IPorterRemoteProcess? = null

            override fun addUserService(conn: IPorterServiceConnection?, args: Bundle?): Int = 0

            override fun removeUserService(conn: IPorterServiceConnection?, args: Bundle?): Int = 0

            override fun requestPermission(requestCode: Int) {
            }

            override fun checkSelfPermission(): Boolean = false

            override fun shouldShowRequestPermissionRationale(): Boolean = false

            override fun exit() {
            }

            override fun attachUserService(binder: IBinder?, args: Bundle?) {
            }

            override fun dispatchPermissionConfirmationResult(requestUid: Int, requestPid: Int, requestCode: Int, data: Bundle?) {
            }

            override fun getFlagsForUid(uid: Int, mask: Int): Int = 0

            override fun updateFlagsForUid(uid: Int, mask: Int, value: Int) {
            }
        }

        fun assertOutsideTheAidlRange(code: Int) {
            assertTrue("code $code", code < IBinder.FIRST_CALL_TRANSACTION || code > LAST_AIDL_CODE)
        }
    }
}
