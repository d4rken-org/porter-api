package eu.darken.porter.endpoint;

import static eu.darken.porter.protocol.PorterProtocol.ATTACH_PACKAGE_NAME;
import static eu.darken.porter.protocol.PorterProtocol.ATTACH_PROTOCOL_VERSION;
import static eu.darken.porter.protocol.PorterProtocol.PERMISSION_CONFIRMATION_ALLOWED;
import static eu.darken.porter.protocol.PorterProtocol.PERMISSION_CONFIRMATION_ONETIME;
import static eu.darken.porter.protocol.PorterProtocol.REPLY_PROTOCOL_VERSION;
import static eu.darken.porter.protocol.PorterProtocol.USER_SERVICE_TOKEN;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static rikka.shizuku.server.ServerTestSupport.newCore;

import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowBinder;

import java.util.Collections;

import eu.darken.porter.core.ManagerOperations;
import eu.darken.porter.server.IPorterApplication;
import eu.darken.porter.server.IPorterServiceConnection;
import rikka.shizuku.server.ClientManager;
import rikka.shizuku.server.ConfigManager;
import rikka.shizuku.server.ServerTestSupport.TestPolicy;
import rikka.shizuku.server.ServerTestSupport.TestUserServiceManager;
import rikka.shizuku.server.util.HandlerUtil;

/** The manager-only Porter methods: who reaches the delegate, and with which arguments. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class PorterEndpointManagerOperationsTest {

    private static final String PACKAGE = "eu.darken.porter";
    private static final String TOKEN = "user-service-token";
    /** User 10, app id 10200, so the user id derived from it is not the trivial zero. */
    private static final int CLIENT_UID = 1010200;
    private static final int CLIENT_PID = 45678;

    private static final int TARGET_UID = 1010300;
    private static final int TARGET_PID = 45700;
    private static final int REQUEST_CODE = 11;

    private TestPolicy policy;
    private ManagerOperations managerOperations;
    private PorterEndpoint endpoint;

    @Before
    public void setup() {
        HandlerUtil.setMainHandler(mock(Handler.class));
        ConfigManager config = mock(ConfigManager.class);
        policy = new TestPolicy();
        managerOperations = mock(ManagerOperations.class);
        endpoint = new PorterEndpoint(
                newCore(new ClientManager<>(config), new TestUserServiceManager(), config, policy,
                        uid -> Collections.singletonList(PACKAGE)),
                managerOperations);

        ShadowBinder.setCallingUid(CLIENT_UID);
        ShadowBinder.setCallingPid(CLIENT_PID);
    }

    @After
    public void teardown() {
        ShadowBinder.reset();
    }

    private static Bundle tokenArgs() {
        Bundle args = new Bundle();
        args.putString(USER_SERVICE_TOKEN, TOKEN);
        return args;
    }

    private static Bundle confirmation(boolean allowed, boolean onetime) {
        Bundle data = new Bundle();
        data.putBoolean(PERMISSION_CONFIRMATION_ALLOWED, allowed);
        data.putBoolean(PERMISSION_CONFIRMATION_ONETIME, onetime);
        return data;
    }

    private static IPorterServiceConnection connection() {
        IPorterServiceConnection connection = mock(IPorterServiceConnection.class);
        when(connection.asBinder()).thenReturn(mock(IBinder.class));
        return connection;
    }

    @Test
    public void aCallerThePolicyDoesNotAcceptReachesNoneOfThem() {
        IBinder binder = mock(IBinder.class);

        assertThrows(SecurityException.class, () -> endpoint.exit());
        assertThrows(SecurityException.class, () -> endpoint.attachUserService(binder, tokenArgs()));
        assertThrows(SecurityException.class, () -> endpoint.dispatchPermissionConfirmationResult(
                TARGET_UID, TARGET_PID, REQUEST_CODE, confirmation(true, false)));
        assertThrows(SecurityException.class, () -> endpoint.getFlagsForUid(TARGET_UID, 6));
        assertThrows(SecurityException.class, () -> endpoint.updateFlagsForUid(TARGET_UID, 6, 2));

        verifyNoInteractions(managerOperations);
    }

    @Test
    public void anAcceptedCallerExits() {
        policy.managerPermission = true;

        endpoint.exit();

        verify(managerOperations).exit();
    }

    @Test
    public void anAcceptedCallerHandsOverTheUserServiceBinderWithItsToken() {
        policy.managerPermission = true;
        IBinder binder = mock(IBinder.class);

        endpoint.attachUserService(binder, tokenArgs());

        verify(managerOperations).attachUserService(binder, TOKEN);
    }

    @Test
    public void aConfirmationResultIsPassedOnAsItsTwoFlags() {
        policy.managerPermission = true;

        endpoint.dispatchPermissionConfirmationResult(
                TARGET_UID, TARGET_PID, REQUEST_CODE, confirmation(true, true));

        verify(managerOperations).dispatchPermissionConfirmationResult(
                TARGET_UID, TARGET_PID, REQUEST_CODE, true, true);
    }

    @Test
    public void aConfirmationResultWithoutDataIsDropped() {
        policy.managerPermission = true;

        endpoint.dispatchPermissionConfirmationResult(TARGET_UID, TARGET_PID, REQUEST_CODE, null);

        verifyNoInteractions(managerOperations);
    }

    @Test
    public void theFlagsForAUidAreReadAndWrittenThroughTheDelegate() {
        policy.managerPermission = true;
        when(managerOperations.getFlagsForUid(TARGET_UID, 6)).thenReturn(2);

        assertEquals(2, endpoint.getFlagsForUid(TARGET_UID, 6));

        endpoint.updateFlagsForUid(TARGET_UID, 6, 2);

        verify(managerOperations).updateFlagsForUid(TARGET_UID, 6, 2);
    }

    @Test
    public void theAttachReplyCarriesTheCurrentProtocolVersion() {
        IPorterApplication application = mock(IPorterApplication.class);
        when(application.asBinder()).thenReturn(mock(IBinder.class));
        Bundle args = new Bundle();
        args.putString(ATTACH_PACKAGE_NAME, PACKAGE);
        args.putInt(ATTACH_PROTOCOL_VERSION, 3);

        Bundle reply = endpoint.attach(application, args);

        assertEquals(3, reply.getInt(REPLY_PROTOCOL_VERSION));
    }

    /**
     * A refused caller is told it has no permission, not what its Bundle failed to decode to: a
     * null or component-less Bundle would raise something else if the gate ran after the decoder.
     */
    @Test
    public void theUserServiceCallsRefuseAnUnauthorizedCallerBeforeReadingItsBundle() {
        assertThrows(SecurityException.class, () -> endpoint.addUserService(connection(), null));
        assertThrows(SecurityException.class, () -> endpoint.addUserService(connection(), new Bundle()));
        assertThrows(SecurityException.class, () -> endpoint.removeUserService(connection(), null));
        assertThrows(SecurityException.class, () -> endpoint.removeUserService(connection(), new Bundle()));
    }
}
