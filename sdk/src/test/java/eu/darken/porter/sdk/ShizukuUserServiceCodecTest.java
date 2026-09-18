package eu.darken.porter.sdk;

import static eu.darken.porter.sdk.ShizukuProtocol.USER_SERVICE_ARG_COMPONENT;
import static eu.darken.porter.sdk.ShizukuProtocol.USER_SERVICE_ARG_DAEMON;
import static eu.darken.porter.sdk.ShizukuProtocol.USER_SERVICE_ARG_DEBUGGABLE;
import static eu.darken.porter.sdk.ShizukuProtocol.USER_SERVICE_ARG_NO_CREATE;
import static eu.darken.porter.sdk.ShizukuProtocol.USER_SERVICE_ARG_PROCESS_NAME;
import static eu.darken.porter.sdk.ShizukuProtocol.USER_SERVICE_ARG_REMOVE;
import static eu.darken.porter.sdk.ShizukuProtocol.USER_SERVICE_ARG_TAG;
import static eu.darken.porter.sdk.ShizukuProtocol.USER_SERVICE_ARG_USE_32_BIT_APP_PROCESS;
import static eu.darken.porter.sdk.ShizukuProtocol.USER_SERVICE_ARG_VERSION_CODE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.content.ComponentName;
import android.os.Bundle;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * What the Shizuku wire writes into the user service Bundles. Every expectation asserts the key is
 * present as well as its value: a missing boolean key reads as false and would pass on its own.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class ShizukuUserServiceCodecTest {

    private static final String PACKAGE = "eu.darken.porter.probe";
    private static final String CLASS = "ProbeService";
    private static final ComponentName COMPONENT = new ComponentName(PACKAGE, CLASS);
    private static final String PROCESS_NAME_SUFFIX = "probe";

    private static Porter.UserServiceArgs args() {
        return new Porter.UserServiceArgs(COMPONENT).processNameSuffix(PROCESS_NAME_SUFFIX);
    }

    @Test
    public void theAddBundleCarriesTheDefaults() {
        Bundle bundle = ShizukuUserServiceCodec.encodeUserService(args(), false);

        assertTrue(bundle.containsKey(USER_SERVICE_ARG_COMPONENT));
        assertEquals(COMPONENT, bundle.getParcelable(USER_SERVICE_ARG_COMPONENT));
        assertTrue(bundle.containsKey(USER_SERVICE_ARG_PROCESS_NAME));
        assertEquals(PROCESS_NAME_SUFFIX, bundle.getString(USER_SERVICE_ARG_PROCESS_NAME));
        assertTrue(bundle.containsKey(USER_SERVICE_ARG_VERSION_CODE));
        assertEquals(1, bundle.getInt(USER_SERVICE_ARG_VERSION_CODE));
        assertTrue(bundle.containsKey(USER_SERVICE_ARG_DAEMON));
        assertTrue(bundle.getBoolean(USER_SERVICE_ARG_DAEMON));
        assertTrue(bundle.containsKey(USER_SERVICE_ARG_DEBUGGABLE));
        assertFalse(bundle.getBoolean(USER_SERVICE_ARG_DEBUGGABLE));
        assertTrue(bundle.containsKey(USER_SERVICE_ARG_USE_32_BIT_APP_PROCESS));
        assertFalse(bundle.getBoolean(USER_SERVICE_ARG_USE_32_BIT_APP_PROCESS));
        assertFalse("no tag key unless a tag was set", bundle.containsKey(USER_SERVICE_ARG_TAG));
        assertFalse("no no-create key unless the peek asked for it",
                bundle.containsKey(USER_SERVICE_ARG_NO_CREATE));
    }

    @Test
    public void theAddBundleCarriesWhatWasSet() {
        Bundle bundle = ShizukuUserServiceCodec.encodeUserService(
                args().tag("probe-tag").version(3).daemon(false).debuggable(true), false);

        assertTrue(bundle.containsKey(USER_SERVICE_ARG_TAG));
        assertEquals("probe-tag", bundle.getString(USER_SERVICE_ARG_TAG));
        assertEquals(3, bundle.getInt(USER_SERVICE_ARG_VERSION_CODE));
        assertFalse(bundle.getBoolean(USER_SERVICE_ARG_DAEMON));
        assertTrue(bundle.getBoolean(USER_SERVICE_ARG_DEBUGGABLE));
    }

    @Test
    public void aPeekAsksForNoCreation() {
        Bundle bundle = ShizukuUserServiceCodec.encodeUserService(args(), true);

        assertTrue(bundle.containsKey(USER_SERVICE_ARG_NO_CREATE));
        assertTrue(bundle.getBoolean(USER_SERVICE_ARG_NO_CREATE));
    }

    /** The setter is private on the args, so the flag is set where the codec reads it. */
    @Test
    public void a32BitServiceSaysSo() {
        Porter.UserServiceArgs args = args();
        args.use32BitAppProcess = true;

        Bundle bundle = ShizukuUserServiceCodec.encodeUserService(args, false);

        assertTrue(bundle.getBoolean(USER_SERVICE_ARG_USE_32_BIT_APP_PROCESS));
    }

    @Test
    public void aServiceWithoutAProcessNameSuffixIsRefused() {
        NullPointerException thrown = assertThrows(NullPointerException.class,
                () -> ShizukuUserServiceCodec.encodeUserService(
                        new Porter.UserServiceArgs(COMPONENT), false));

        assertEquals("process name suffix must not be null", thrown.getMessage());
    }

    @Test
    public void theRemovalBundleCarriesTheRemoveFlag() {
        Bundle remove = ShizukuUserServiceCodec.encodeUserServiceRemoval(args().tag("probe-tag"), true);

        assertEquals(COMPONENT, remove.getParcelable(USER_SERVICE_ARG_COMPONENT));
        assertEquals("probe-tag", remove.getString(USER_SERVICE_ARG_TAG));
        assertTrue(remove.containsKey(USER_SERVICE_ARG_REMOVE));
        assertTrue(remove.getBoolean(USER_SERVICE_ARG_REMOVE));
        assertFalse("a removal names nothing about the process",
                remove.containsKey(USER_SERVICE_ARG_PROCESS_NAME));

        Bundle keep = ShizukuUserServiceCodec.encodeUserServiceRemoval(args(), false);
        assertTrue(keep.containsKey(USER_SERVICE_ARG_REMOVE));
        assertFalse(keep.getBoolean(USER_SERVICE_ARG_REMOVE));
        assertFalse("no tag key unless a tag was set", keep.containsKey(USER_SERVICE_ARG_TAG));
    }
}
