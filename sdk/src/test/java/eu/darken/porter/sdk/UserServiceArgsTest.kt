package eu.darken.porter.sdk

import android.content.ComponentName
import eu.darken.porter.protocol.PorterProtocol.USER_SERVICE_COMPONENT
import eu.darken.porter.protocol.PorterProtocol.USER_SERVICE_DAEMON
import eu.darken.porter.protocol.PorterProtocol.USER_SERVICE_DEBUGGABLE
import eu.darken.porter.protocol.PorterProtocol.USER_SERVICE_PROCESS_NAME_SUFFIX
import eu.darken.porter.protocol.PorterProtocol.USER_SERVICE_REMOVE
import eu.darken.porter.protocol.PorterProtocol.USER_SERVICE_TAG
import eu.darken.porter.protocol.PorterProtocol.USER_SERVICE_USE_32_BIT
import eu.darken.porter.protocol.PorterProtocol.USER_SERVICE_VERSION_CODE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What a client writes into the user service Bundles. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
internal class UserServiceArgsTest {

    @Test
    fun theAddBundleCarriesTheDefaults() {
        val bundle = PorterUserServiceCodec.encodeUserService(args(), false)

        assertEquals(COMPONENT, bundle.getParcelable<ComponentName>(USER_SERVICE_COMPONENT))
        assertEquals(PROCESS_NAME_SUFFIX, bundle.getString(USER_SERVICE_PROCESS_NAME_SUFFIX))
        assertEquals(1, bundle.getInt(USER_SERVICE_VERSION_CODE))
        assertTrue(bundle.getBoolean(USER_SERVICE_DAEMON))
        assertFalse(bundle.getBoolean(USER_SERVICE_DEBUGGABLE))
        assertFalse(bundle.getBoolean(USER_SERVICE_USE_32_BIT))
        assertFalse("no tag key unless a tag was set", bundle.containsKey(USER_SERVICE_TAG))
    }

    @Test
    fun theAddBundleCarriesWhatWasSet() {
        val bundle = PorterUserServiceCodec.encodeUserService(
            args().copy(tag = "probe-tag", version = 3, daemon = false, debuggable = true), false,
        )

        assertEquals("probe-tag", bundle.getString(USER_SERVICE_TAG))
        assertEquals(3, bundle.getInt(USER_SERVICE_VERSION_CODE))
        assertFalse(bundle.getBoolean(USER_SERVICE_DAEMON))
        assertTrue(bundle.getBoolean(USER_SERVICE_DEBUGGABLE))
    }

    @Test
    fun theRemovalBundleCarriesTheRemoveFlag() {
        val remove = PorterUserServiceCodec.encodeUserServiceRemoval(args().copy(tag = "probe-tag"), true)

        assertEquals(COMPONENT, remove.getParcelable<ComponentName>(USER_SERVICE_COMPONENT))
        assertEquals("probe-tag", remove.getString(USER_SERVICE_TAG))
        assertTrue(remove.getBoolean(USER_SERVICE_REMOVE))

        assertFalse(PorterUserServiceCodec.encodeUserServiceRemoval(args(), false).getBoolean(USER_SERVICE_REMOVE))
    }

    private companion object {
        const val PACKAGE = "eu.darken.porter.probe"
        const val CLASS = "ProbeService"
        val COMPONENT = ComponentName(PACKAGE, CLASS)
        const val PROCESS_NAME_SUFFIX = "probe"

        fun args(): UserServiceArgs = UserServiceArgs(COMPONENT, processNameSuffix = PROCESS_NAME_SUFFIX)
    }
}
