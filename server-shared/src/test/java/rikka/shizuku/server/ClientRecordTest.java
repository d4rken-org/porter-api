package rikka.shizuku.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import android.os.Bundle;
import android.os.RemoteException;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Set;

import moe.shizuku.server.IShizukuApplication;
import rikka.shizuku.ShizukuApiConstants;

/** The permission-result callback {@link ClientRecord} sends, and what a failing send does. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class ClientRecordTest {

    private static final int UID = 10200;
    private static final int PID = 45678;

    @Test
    public void dispatchRequestPermissionResultSendsTheAllowedKey() throws Exception {
        IShizukuApplication application = mock(IShizukuApplication.class);
        ClientRecord record = new ClientRecord(UID, PID, application, "eu.darken.porter.probe", 13);

        record.dispatchRequestPermissionResult(21, true);

        ArgumentCaptor<Bundle> reply = ArgumentCaptor.forClass(Bundle.class);
        verify(application).dispatchRequestPermissionResult(eq(21), reply.capture());
        assertEquals(Set.of(ShizukuApiConstants.REQUEST_PERMISSION_REPLY_ALLOWED), reply.getValue().keySet());
        assertTrue(reply.getValue().getBoolean(ShizukuApiConstants.REQUEST_PERMISSION_REPLY_ALLOWED));
    }

    @Test
    public void aThrowingClientIsLoggedNotPropagated() throws Exception {
        IShizukuApplication application = mock(IShizukuApplication.class);
        doThrow(new RemoteException("dead"))
                .when(application).dispatchRequestPermissionResult(anyInt(), any(Bundle.class));
        ClientRecord record = new ClientRecord(UID, PID, application, "eu.darken.porter.probe", 13);

        record.dispatchRequestPermissionResult(23, false);

        verify(application).dispatchRequestPermissionResult(eq(23), any(Bundle.class));
    }
}
