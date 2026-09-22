package eu.darken.porter.sdk

import android.content.ComponentName
import android.os.Bundle
import eu.darken.porter.protocol.PorterProtocol.USER_SERVICE_COMPONENT
import eu.darken.porter.protocol.PorterProtocol.USER_SERVICE_DAEMON
import eu.darken.porter.protocol.PorterProtocol.USER_SERVICE_DEBUGGABLE
import eu.darken.porter.protocol.PorterProtocol.USER_SERVICE_NO_CREATE
import eu.darken.porter.protocol.PorterProtocol.USER_SERVICE_PROCESS_NAME_SUFFIX
import eu.darken.porter.protocol.PorterProtocol.USER_SERVICE_REMOVE
import eu.darken.porter.protocol.PorterProtocol.USER_SERVICE_TAG
import eu.darken.porter.protocol.PorterProtocol.USER_SERVICE_USE_32_BIT
import eu.darken.porter.protocol.PorterProtocol.USER_SERVICE_VERSION_CODE
import eu.darken.porter.sdk.ShizukuProtocol.USER_SERVICE_ARG_COMPONENT
import eu.darken.porter.sdk.ShizukuProtocol.USER_SERVICE_ARG_DAEMON
import eu.darken.porter.sdk.ShizukuProtocol.USER_SERVICE_ARG_DEBUGGABLE
import eu.darken.porter.sdk.ShizukuProtocol.USER_SERVICE_ARG_NO_CREATE
import eu.darken.porter.sdk.ShizukuProtocol.USER_SERVICE_ARG_PROCESS_NAME
import eu.darken.porter.sdk.ShizukuProtocol.USER_SERVICE_ARG_REMOVE
import eu.darken.porter.sdk.ShizukuProtocol.USER_SERVICE_ARG_TAG
import eu.darken.porter.sdk.ShizukuProtocol.USER_SERVICE_ARG_USE_32_BIT_APP_PROCESS
import eu.darken.porter.sdk.ShizukuProtocol.USER_SERVICE_ARG_VERSION_CODE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What the Shizuku wire writes into the user service Bundles. Every expectation asserts the key is
 * present as well as its value: a missing boolean key reads as false and would pass on its own.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
internal class ShizukuUserServiceCodecTest {

    private fun args(): UserServiceArgs = UserServiceArgs(COMPONENT, processNameSuffix = PROCESS_NAME_SUFFIX)

    @Suppress("DEPRECATION")
    private fun Bundle.component(key: String): ComponentName? = getParcelable(key)

    @Test
    fun theAddBundleCarriesTheDefaults() {
        val bundle = ShizukuUserServiceCodec.encodeUserService(args(), false)

        assertTrue(bundle.containsKey(USER_SERVICE_ARG_COMPONENT))
        assertEquals(COMPONENT, bundle.component(USER_SERVICE_ARG_COMPONENT))
        assertTrue(bundle.containsKey(USER_SERVICE_ARG_PROCESS_NAME))
        assertEquals(PROCESS_NAME_SUFFIX, bundle.getString(USER_SERVICE_ARG_PROCESS_NAME))
        assertTrue(bundle.containsKey(USER_SERVICE_ARG_VERSION_CODE))
        assertEquals(1, bundle.getInt(USER_SERVICE_ARG_VERSION_CODE))
        assertTrue(bundle.containsKey(USER_SERVICE_ARG_DAEMON))
        assertFalse(bundle.getBoolean(USER_SERVICE_ARG_DAEMON))
        assertTrue(bundle.containsKey(USER_SERVICE_ARG_DEBUGGABLE))
        assertFalse(bundle.getBoolean(USER_SERVICE_ARG_DEBUGGABLE))
        assertTrue(bundle.containsKey(USER_SERVICE_ARG_USE_32_BIT_APP_PROCESS))
        assertFalse(bundle.getBoolean(USER_SERVICE_ARG_USE_32_BIT_APP_PROCESS))
        assertFalse("no tag key unless a tag was set", bundle.containsKey(USER_SERVICE_ARG_TAG))
        assertFalse("no no-create key unless the peek asked for it", bundle.containsKey(USER_SERVICE_ARG_NO_CREATE))
    }

    @Test
    fun theAddBundleCarriesWhatWasSet() {
        val bundle = ShizukuUserServiceCodec.encodeUserService(
            args().copy(tag = "probe-tag", version = 3, daemon = true, debuggable = true),
            false,
        )

        assertTrue(bundle.containsKey(USER_SERVICE_ARG_TAG))
        assertEquals("probe-tag", bundle.getString(USER_SERVICE_ARG_TAG))
        assertEquals(3, bundle.getInt(USER_SERVICE_ARG_VERSION_CODE))
        assertTrue(bundle.getBoolean(USER_SERVICE_ARG_DAEMON))
        assertTrue(bundle.getBoolean(USER_SERVICE_ARG_DEBUGGABLE))
    }

    @Test
    fun aPeekAsksForNoCreation() {
        val bundle = ShizukuUserServiceCodec.encodeUserService(args(), true)

        assertTrue(bundle.containsKey(USER_SERVICE_ARG_NO_CREATE))
        assertTrue(bundle.getBoolean(USER_SERVICE_ARG_NO_CREATE))
    }

    /** The args carry no 32-bit switch, so the key the server reads is always written and false. */
    @Test
    fun a32BitServiceIsNeverAskedFor() {
        val bundle = ShizukuUserServiceCodec.encodeUserService(args(), false)

        assertTrue(bundle.containsKey(USER_SERVICE_ARG_USE_32_BIT_APP_PROCESS))
        assertFalse(bundle.getBoolean(USER_SERVICE_ARG_USE_32_BIT_APP_PROCESS))
    }

    @Test
    fun theRemovalBundleCarriesTheRemoveFlag() {
        val remove = ShizukuUserServiceCodec.encodeUserServiceRemoval(args().copy(tag = "probe-tag"), true)

        assertEquals(COMPONENT, remove.component(USER_SERVICE_ARG_COMPONENT))
        assertEquals("probe-tag", remove.getString(USER_SERVICE_ARG_TAG))
        assertTrue(remove.containsKey(USER_SERVICE_ARG_REMOVE))
        assertTrue(remove.getBoolean(USER_SERVICE_ARG_REMOVE))
        assertFalse("a removal names nothing about the process", remove.containsKey(USER_SERVICE_ARG_PROCESS_NAME))

        val keep = ShizukuUserServiceCodec.encodeUserServiceRemoval(args(), false)
        assertTrue(keep.containsKey(USER_SERVICE_ARG_REMOVE))
        assertFalse(keep.getBoolean(USER_SERVICE_ARG_REMOVE))
        assertFalse("no tag key unless a tag was set", keep.containsKey(USER_SERVICE_ARG_TAG))
    }

    /**
     * One args object written by both codecs, each in its own key space. A pair that drifts apart
     * leaves one server reading a default where the other reads what the caller asked for.
     */
    @Test
    fun bothCodecsWriteTheSameAddArguments() {
        val populated = args().copy(tag = "probe-tag", version = 3, daemon = false, debuggable = true)

        assertAddBundlesAgree(populated, true)
        assertAddBundlesAgree(args(), false)
    }

    @Test
    fun bothCodecsWriteTheSameRemovalArguments() {
        assertRemovalBundlesAgree(args().copy(tag = "probe-tag"), true)
        assertRemovalBundlesAgree(args().copy(tag = "probe-tag"), false)
        assertRemovalBundlesAgree(args(), true)
        assertRemovalBundlesAgree(args(), false)
    }

    private fun assertAddBundlesAgree(args: UserServiceArgs, noCreate: Boolean) {
        val porter = PorterUserServiceCodec.encodeUserService(args, noCreate)
        val shizuku = ShizukuUserServiceCodec.encodeUserService(args, noCreate)

        assertBothCarry(porter, USER_SERVICE_COMPONENT, shizuku, USER_SERVICE_ARG_COMPONENT)
        assertEquals(
            "the component pair disagrees",
            porter.component(USER_SERVICE_COMPONENT),
            shizuku.component(USER_SERVICE_ARG_COMPONENT),
        )

        assertBothCarry(porter, USER_SERVICE_PROCESS_NAME_SUFFIX, shizuku, USER_SERVICE_ARG_PROCESS_NAME)
        assertEquals(
            "the process name pair disagrees",
            porter.getString(USER_SERVICE_PROCESS_NAME_SUFFIX),
            shizuku.getString(USER_SERVICE_ARG_PROCESS_NAME),
        )

        assertBothCarry(porter, USER_SERVICE_VERSION_CODE, shizuku, USER_SERVICE_ARG_VERSION_CODE)
        assertEquals(
            "the version code pair disagrees",
            porter.getInt(USER_SERVICE_VERSION_CODE),
            shizuku.getInt(USER_SERVICE_ARG_VERSION_CODE),
        )

        assertBooleanPairAgrees(porter, USER_SERVICE_DAEMON, shizuku, USER_SERVICE_ARG_DAEMON)
        assertBooleanPairAgrees(porter, USER_SERVICE_DEBUGGABLE, shizuku, USER_SERVICE_ARG_DEBUGGABLE)
        assertBooleanPairAgrees(porter, USER_SERVICE_USE_32_BIT, shizuku, USER_SERVICE_ARG_USE_32_BIT_APP_PROCESS)

        if (args.tag != null) {
            assertBothCarry(porter, USER_SERVICE_TAG, shizuku, USER_SERVICE_ARG_TAG)
            assertEquals("the tag pair disagrees", porter.getString(USER_SERVICE_TAG), shizuku.getString(USER_SERVICE_ARG_TAG))
        }
        if (noCreate) {
            assertBooleanPairAgrees(porter, USER_SERVICE_NO_CREATE, shizuku, USER_SERVICE_ARG_NO_CREATE)
        }

        val porterKeys = mutableSetOf(
            USER_SERVICE_COMPONENT, USER_SERVICE_PROCESS_NAME_SUFFIX, USER_SERVICE_VERSION_CODE,
            USER_SERVICE_DAEMON, USER_SERVICE_DEBUGGABLE, USER_SERVICE_USE_32_BIT,
        )
        val shizukuKeys = mutableSetOf(
            USER_SERVICE_ARG_COMPONENT, USER_SERVICE_ARG_PROCESS_NAME, USER_SERVICE_ARG_VERSION_CODE,
            USER_SERVICE_ARG_DAEMON, USER_SERVICE_ARG_DEBUGGABLE, USER_SERVICE_ARG_USE_32_BIT_APP_PROCESS,
        )
        if (args.tag != null) {
            porterKeys.add(USER_SERVICE_TAG)
            shizukuKeys.add(USER_SERVICE_ARG_TAG)
        }
        if (noCreate) {
            porterKeys.add(USER_SERVICE_NO_CREATE)
            shizukuKeys.add(USER_SERVICE_ARG_NO_CREATE)
        }
        assertEquals("the Porter bundle carries a key outside the pairs", porterKeys, porter.keySet())
        assertEquals("the Shizuku bundle carries a key outside the pairs", shizukuKeys, shizuku.keySet())
    }

    private fun assertRemovalBundlesAgree(args: UserServiceArgs, remove: Boolean) {
        val porter = PorterUserServiceCodec.encodeUserServiceRemoval(args, remove)
        val shizuku = ShizukuUserServiceCodec.encodeUserServiceRemoval(args, remove)

        assertBothCarry(porter, USER_SERVICE_COMPONENT, shizuku, USER_SERVICE_ARG_COMPONENT)
        assertEquals(
            "the component pair disagrees",
            porter.component(USER_SERVICE_COMPONENT),
            shizuku.component(USER_SERVICE_ARG_COMPONENT),
        )

        assertBooleanPairAgrees(porter, USER_SERVICE_REMOVE, shizuku, USER_SERVICE_ARG_REMOVE)

        if (args.tag != null) {
            assertBothCarry(porter, USER_SERVICE_TAG, shizuku, USER_SERVICE_ARG_TAG)
            assertEquals("the tag pair disagrees", porter.getString(USER_SERVICE_TAG), shizuku.getString(USER_SERVICE_ARG_TAG))
        }

        val porterKeys = mutableSetOf(USER_SERVICE_COMPONENT, USER_SERVICE_REMOVE)
        val shizukuKeys = mutableSetOf(USER_SERVICE_ARG_COMPONENT, USER_SERVICE_ARG_REMOVE)
        if (args.tag != null) {
            porterKeys.add(USER_SERVICE_TAG)
            shizukuKeys.add(USER_SERVICE_ARG_TAG)
        }
        assertEquals("the Porter bundle carries a key outside the pairs", porterKeys, porter.keySet())
        assertEquals("the Shizuku bundle carries a key outside the pairs", shizukuKeys, shizuku.keySet())
    }

    private fun assertBooleanPairAgrees(porter: Bundle, porterKey: String, shizuku: Bundle, shizukuKey: String) {
        assertBothCarry(porter, porterKey, shizuku, shizukuKey)
        assertEquals("$porterKey and $shizukuKey disagree", porter.getBoolean(porterKey), shizuku.getBoolean(shizukuKey))
    }

    private fun assertBothCarry(porter: Bundle, porterKey: String, shizuku: Bundle, shizukuKey: String) {
        assertTrue("the Porter bundle carries no $porterKey", porter.containsKey(porterKey))
        assertTrue("the Shizuku bundle carries no $shizukuKey", shizuku.containsKey(shizukuKey))
    }

    private companion object {
        const val PACKAGE = "eu.darken.porter.probe"
        const val CLASS = "ProbeService"
        val COMPONENT = ComponentName(PACKAGE, CLASS)
        const val PROCESS_NAME_SUFFIX = "probe"
    }
}
