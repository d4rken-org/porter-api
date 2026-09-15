package rikka.shizuku.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static rikka.shizuku.server.ServerTestSupport.newCore;

import android.content.Intent;
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
import rikka.shizuku.ShizukuApiConstants;
import rikka.shizuku.server.ServerTestSupport.TestPolicy;
import rikka.shizuku.server.ServerTestSupport.TestUserServiceManager;
import rikka.shizuku.server.util.HandlerUtil;

/** The manager-only Shizuku methods: who reaches the delegate, and with which arguments. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class ShizukuLegacyEndpointManagerOperationsTest {

    private static final String PACKAGE = "eu.darken.porter.probe";
    private static final String TOKEN = "user-service-token";
    /** User 10, app id 10200, so the user id derived from it is not the trivial zero. */
    private static final int CLIENT_UID = 1010200;
    private static final int CLIENT_PID = 45678;

    private static final int TARGET_UID = 1010300;
    private static final int TARGET_PID = 45700;
    private static final int REQUEST_CODE = 11;

    private TestPolicy policy;
    private ManagerOperations managerOperations;
    private ShizukuLegacyEndpoint endpoint;

    @Before
    public void setup() {
        HandlerUtil.setMainHandler(mock(Handler.class));
        ConfigManager config = mock(ConfigManager.class);
        policy = new TestPolicy();
        managerOperations = mock(ManagerOperations.class);
        endpoint = new ShizukuLegacyEndpoint(
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

    private static Bundle tokenOptions() {
        Bundle options = new Bundle();
        options.putString(ShizukuApiConstants.USER_SERVICE_ARG_TOKEN, TOKEN);
        return options;
    }

    private static Bundle confirmation(boolean allowed, boolean onetime) {
        Bundle data = new Bundle();
        data.putBoolean(ShizukuApiConstants.REQUEST_PERMISSION_REPLY_ALLOWED, allowed);
        data.putBoolean(ShizukuApiConstants.REQUEST_PERMISSION_REPLY_IS_ONETIME, onetime);
        return data;
    }

    @Test
    public void aCallerThePolicyDoesNotAcceptReachesNoneOfThem() {
        IBinder binder = mock(IBinder.class);

        assertThrows(SecurityException.class, () -> endpoint.exit());
        assertThrows(SecurityException.class, () -> endpoint.attachUserService(binder, tokenOptions()));
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

        endpoint.attachUserService(binder, tokenOptions());

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
    public void theSuiMethodsAreInert() {
        assertFalse(endpoint.isHidden(TARGET_UID));
        endpoint.dispatchPackageChanged(new Intent());

        verifyNoInteractions(managerOperations);
    }
}
