package eu.darken.porter.sdk;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import moe.shizuku.server.IShizukuServiceConnection;

import rikka.shizuku.ShizukuApiConstants;

/**
 * The Shizuku strings are copied by hand, because the modules that declare them are not published.
 * This is what catches a mistyped copy.
 */
public class ShizukuProtocolConstantsTest {

    @Test
    public void everyUserServiceKeyIsTheOneTheServerReads() {
        assertEquals(ShizukuApiConstants.USER_SERVICE_ARG_TAG, ShizukuProtocol.USER_SERVICE_ARG_TAG);
        assertEquals(ShizukuApiConstants.USER_SERVICE_ARG_COMPONENT,
                ShizukuProtocol.USER_SERVICE_ARG_COMPONENT);
        assertEquals(ShizukuApiConstants.USER_SERVICE_ARG_DEBUGGABLE,
                ShizukuProtocol.USER_SERVICE_ARG_DEBUGGABLE);
        assertEquals(ShizukuApiConstants.USER_SERVICE_ARG_VERSION_CODE,
                ShizukuProtocol.USER_SERVICE_ARG_VERSION_CODE);
        assertEquals(ShizukuApiConstants.USER_SERVICE_ARG_PROCESS_NAME,
                ShizukuProtocol.USER_SERVICE_ARG_PROCESS_NAME);
        assertEquals(ShizukuApiConstants.USER_SERVICE_ARG_NO_CREATE,
                ShizukuProtocol.USER_SERVICE_ARG_NO_CREATE);
        assertEquals(ShizukuApiConstants.USER_SERVICE_ARG_DAEMON,
                ShizukuProtocol.USER_SERVICE_ARG_DAEMON);
        assertEquals(ShizukuApiConstants.USER_SERVICE_ARG_USE_32_BIT_APP_PROCESS,
                ShizukuProtocol.USER_SERVICE_ARG_USE_32_BIT_APP_PROCESS);
        assertEquals(ShizukuApiConstants.USER_SERVICE_ARG_REMOVE,
                ShizukuProtocol.USER_SERVICE_ARG_REMOVE);
        assertEquals(ShizukuApiConstants.USER_SERVICE_ARG_TOKEN,
                ShizukuProtocol.USER_SERVICE_ARG_TOKEN);
    }

    @Test
    public void theConnectionDescriptorIsTheOneTheAidlDeclares() {
        assertEquals(IShizukuServiceConnection.DESCRIPTOR,
                ShizukuProtocol.SERVICE_CONNECTION_DESCRIPTOR);
    }
}
