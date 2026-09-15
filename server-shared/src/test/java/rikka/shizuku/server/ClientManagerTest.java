package rikka.shizuku.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static rikka.shizuku.server.ServiceCallerGateTest.application;
import static rikka.shizuku.server.ServiceCallerGateTest.entry;

import android.os.IBinder;
import android.os.RemoteException;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.List;

import moe.shizuku.server.IShizukuApplication;

/** What {@link ClientManager} records, how it seeds {@code allowed}, and when it forgets a record. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class ClientManagerTest {

    private static final String PACKAGE = "eu.darken.porter.probe";
    private static final int UID = 10200;
    private static final int PID = 45678;

    private ConfigManager config;
    private ClientManager<ConfigManager> clients;

    @Before
    public void setup() {
        config = mock(ConfigManager.class);
        clients = new ClientManager<>(config);
    }

    private ClientRecord add(int uid, int pid, IShizukuApplication application) {
        return clients.addClient(uid, pid, application, PACKAGE, 13);
    }

    @Test
    public void addClientSeedsAllowedFromAnAllowedEntry() {
        when(config.find(UID)).thenReturn(entry(true, false));

        ClientRecord record = add(UID, PID, application(mock(IBinder.class)));

        assertNotNull(record);
        assertTrue(record.allowed);
    }

    @Test
    public void addClientLeavesAllowedFalseForNoEntryOrDeniedEntry() {
        assertFalse(add(UID, PID, application(mock(IBinder.class))).allowed);

        when(config.find(UID)).thenReturn(entry(false, true));

        assertFalse(add(UID, PID + 1, application(mock(IBinder.class))).allowed);
    }

    @Test
    public void addClientLinksToDeathAndDeathForgetsTheRecord() throws Exception {
        IBinder binder = mock(IBinder.class);
        ClientRecord record = add(UID, PID, application(binder));
        assertSame(record, clients.findClient(UID, PID));

        ArgumentCaptor<IBinder.DeathRecipient> recipient =
                ArgumentCaptor.forClass(IBinder.DeathRecipient.class);
        verify(binder).linkToDeath(recipient.capture(), eq(0));
        recipient.getValue().binderDied();

        assertNull(clients.findClient(UID, PID));
    }

    @Test
    public void addClientReturnsNullWhenLinkToDeathFails() throws Exception {
        IBinder binder = mock(IBinder.class);
        doThrow(new RemoteException("dead")).when(binder).linkToDeath(any(IBinder.DeathRecipient.class), anyInt());

        assertNull(add(UID, PID, application(binder)));
        assertNull(clients.findClient(UID, PID));
    }

    @Test
    public void findClientsReturnsEveryRecordOfTheUid() {
        ClientRecord first = add(UID, PID, application(mock(IBinder.class)));
        ClientRecord second = add(UID, PID + 1, application(mock(IBinder.class)));
        ClientRecord other = add(UID + 1, PID + 2, application(mock(IBinder.class)));

        assertEquals(List.of(first, second), clients.findClients(UID));
        assertEquals(List.of(other), clients.findClients(UID + 1));
    }

    @Test
    public void requireClientThrowsIllegalStateWhenAbsent() {
        assertThrows(IllegalStateException.class, () -> clients.requireClient(UID, PID));
    }

    @Test
    public void requireClientWithPermissionThrowsSecurityWhenNotAllowed() {
        ClientRecord record = add(UID, PID, application(mock(IBinder.class)));

        assertSame(record, clients.requireClient(UID, PID, false));
        assertThrows(SecurityException.class, () -> clients.requireClient(UID, PID, true));

        record.allowed = true;

        assertSame(record, clients.requireClient(UID, PID, true));
    }
}
