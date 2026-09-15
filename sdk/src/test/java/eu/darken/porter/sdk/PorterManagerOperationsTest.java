package eu.darken.porter.sdk;

import static eu.darken.porter.protocol.PorterProtocol.PERMISSION_CONFIRMATION_ALLOWED;
import static eu.darken.porter.protocol.PorterProtocol.PERMISSION_CONFIRMATION_ONETIME;
import static eu.darken.porter.protocol.PorterProtocol.USER_SERVICE_TOKEN;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

import android.os.Bundle;
import android.os.IBinder;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** What the manager-only calls put on the wire for the server to read back. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class PorterManagerOperationsTest {

    private static final String PACKAGE = "eu.darken.porter";
    private static final String TOKEN = "user-service-token";
    private static final int TARGET_UID = 1010200;
    private static final int TARGET_PID = 45678;
    private static final int REQUEST_CODE = 11;

    private FakePorterService fake;

    @Before
    public void setup() {
        fake = new FakePorterService();
        Porter.onBinderReceived(fake, PACKAGE);
    }

    @After
    public void teardown() {
        Porter.resetForTest();
    }

    @Test
    public void exitReachesTheServer() {
        Porter.exit();

        assertEquals(1, fake.exitCalls);
    }

    @Test
    public void attachUserServiceSendsTheBinderAndItsToken() {
        IBinder binder = mock(IBinder.class);

        Porter.attachUserService(binder, TOKEN);

        assertSame(binder, fake.attachedUserServiceBinder);
        assertNotNull(fake.attachedUserServiceArgs);
        assertEquals(TOKEN, fake.attachedUserServiceArgs.getString(USER_SERVICE_TOKEN));
    }

    @Test
    public void aConfirmationResultSendsBothFlagsWithTheRequest() {
        Porter.dispatchPermissionConfirmationResult(TARGET_UID, TARGET_PID, REQUEST_CODE, true, false);

        assertEquals(TARGET_UID, fake.confirmationUid);
        assertEquals(TARGET_PID, fake.confirmationPid);
        assertEquals(REQUEST_CODE, fake.confirmationRequestCode);
        Bundle data = fake.confirmationData;
        assertNotNull(data);
        assertTrue(data.getBoolean(PERMISSION_CONFIRMATION_ALLOWED));
        assertFalse(data.getBoolean(PERMISSION_CONFIRMATION_ONETIME));
    }

    @Test
    public void theFlagsForAUidAreReadAndWritten() {
        fake.flags = 2;

        assertEquals(2, Porter.getFlagsForUid(TARGET_UID, 6));
        assertEquals(TARGET_UID, fake.flagsUid);
        assertEquals(6, fake.flagsMask);

        Porter.updateFlagsForUid(TARGET_UID, 6, 4);

        assertEquals(6, fake.flagsMask);
        assertEquals(4, fake.flagsValue);
    }
}
