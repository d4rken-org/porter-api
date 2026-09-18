package eu.darken.porter.sdk;

import static eu.darken.porter.protocol.PorterProtocol.USER_SERVICE_COMPONENT;
import static eu.darken.porter.protocol.PorterProtocol.USER_SERVICE_DAEMON;
import static eu.darken.porter.protocol.PorterProtocol.USER_SERVICE_DEBUGGABLE;
import static eu.darken.porter.protocol.PorterProtocol.USER_SERVICE_PROCESS_NAME_SUFFIX;
import static eu.darken.porter.protocol.PorterProtocol.USER_SERVICE_REMOVE;
import static eu.darken.porter.protocol.PorterProtocol.USER_SERVICE_TAG;
import static eu.darken.porter.protocol.PorterProtocol.USER_SERVICE_USE_32_BIT;
import static eu.darken.porter.protocol.PorterProtocol.USER_SERVICE_VERSION_CODE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.ComponentName;
import android.os.Bundle;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** What a client writes into the user service Bundles. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class UserServiceArgsTest {

    private static final String PACKAGE = "eu.darken.porter.probe";
    private static final String CLASS = "ProbeService";
    private static final ComponentName COMPONENT = new ComponentName(PACKAGE, CLASS);
    private static final String PROCESS_NAME_SUFFIX = "probe";

    private static Porter.UserServiceArgs args() {
        return new Porter.UserServiceArgs(COMPONENT).processNameSuffix(PROCESS_NAME_SUFFIX);
    }

    @Test
    public void theAddBundleCarriesTheDefaults() {
        Bundle bundle = PorterUserServiceCodec.encodeUserService(args(), false);

        assertEquals(COMPONENT, bundle.getParcelable(USER_SERVICE_COMPONENT));
        assertEquals(PROCESS_NAME_SUFFIX, bundle.getString(USER_SERVICE_PROCESS_NAME_SUFFIX));
        assertEquals(1, bundle.getInt(USER_SERVICE_VERSION_CODE));
        assertTrue(bundle.getBoolean(USER_SERVICE_DAEMON));
        assertFalse(bundle.getBoolean(USER_SERVICE_DEBUGGABLE));
        assertFalse(bundle.getBoolean(USER_SERVICE_USE_32_BIT));
        assertFalse("no tag key unless a tag was set", bundle.containsKey(USER_SERVICE_TAG));
    }

    @Test
    public void theAddBundleCarriesWhatWasSet() {
        Bundle bundle = PorterUserServiceCodec.encodeUserService(
                args().tag("probe-tag").version(3).daemon(false).debuggable(true), false);

        assertEquals("probe-tag", bundle.getString(USER_SERVICE_TAG));
        assertEquals(3, bundle.getInt(USER_SERVICE_VERSION_CODE));
        assertFalse(bundle.getBoolean(USER_SERVICE_DAEMON));
        assertTrue(bundle.getBoolean(USER_SERVICE_DEBUGGABLE));
    }

    @Test
    public void theRemovalBundleCarriesTheRemoveFlag() {
        Bundle remove = PorterUserServiceCodec.encodeUserServiceRemoval(args().tag("probe-tag"), true);

        assertEquals(COMPONENT, remove.getParcelable(USER_SERVICE_COMPONENT));
        assertEquals("probe-tag", remove.getString(USER_SERVICE_TAG));
        assertTrue(remove.getBoolean(USER_SERVICE_REMOVE));

        assertFalse(PorterUserServiceCodec.encodeUserServiceRemoval(args(), false)
                .getBoolean(USER_SERVICE_REMOVE));
    }
}
