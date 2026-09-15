package eu.darken.porter.endpoint;

import static eu.darken.porter.protocol.PorterProtocol.ATTACH_PACKAGE_NAME;
import static eu.darken.porter.protocol.PorterProtocol.ATTACH_PROTOCOL_VERSION;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static rikka.shizuku.server.ServiceCallerGateTest.entry;
import static rikka.shizuku.server.ServiceCallerGateTest.newService;

import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Parcel;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowBinder;

import java.lang.reflect.Field;
import java.util.Collections;

import eu.darken.porter.porsh.PorshService;
import eu.darken.porter.protocol.PorterProtocol;
import eu.darken.porter.server.IPorterApplication;
import rikka.shizuku.ShizukuApiConstants;
import rikka.shizuku.server.ClientManager;
import rikka.shizuku.server.ConfigManager;
import rikka.shizuku.server.Service;
import rikka.shizuku.server.ServiceCallerGateTest.TestService;
import rikka.shizuku.server.ServiceCallerGateTest.TestUserServiceManager;
import rikka.shizuku.server.util.HandlerUtil;

/**
 * Each endpoint owns its own transaction codes and its own interface token: a raw transaction is
 * answered by the endpoint it was addressed to, and by no other.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class PorterEndpointTransactTest {

    private static final String PACKAGE = "eu.darken.porter.probe";
    /** User 10, app id 10200, so the user id derived from it is not the trivial zero. */
    private static final int CLIENT_UID = 1010200;
    private static final int CLIENT_PID = 45678;

    private static final int LEGACY_PORSH_BASE = 30000;
    private static final int TARGET_CODE = 7;
    private static final int IN_PARCEL_FLAGS = 42;
    private static final int PAYLOAD = 20816;
    private static final int OUTER_FLAGS = 17;

    private ConfigManager config;
    private ClientManager<ConfigManager> clients;
    private TestService service;
    private PorterEndpoint endpoint;

    @Before
    public void setup() {
        HandlerUtil.setMainHandler(mock(Handler.class));
        config = mock(ConfigManager.class);
        clients = new ClientManager<>(config);
        service = newService(clients, new TestUserServiceManager(), config);
        endpoint = new PorterEndpoint(service, uid -> Collections.singletonList(PACKAGE));

        ShadowBinder.setCallingUid(CLIENT_UID);
        ShadowBinder.setCallingPid(CLIENT_PID);
    }

    @After
    public void teardown() {
        ShadowBinder.reset();
    }

    /** {@code newService} runs no constructor, so the legacy service has no shell service yet. */
    private void giveTheLegacyServiceItsShell() {
        PorshService porshService = new PorshService(ShizukuApiConstants.BINDER_DESCRIPTOR, LEGACY_PORSH_BASE) {

            @Override
            public void enforceCallingPermission(String func) {
                service.enforceCallingPermission(func);
            }
        };
        try {
            Field field = Service.class.getDeclaredField("porshService");
            field.setAccessible(true);
            field.set(service, porshService);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private void attachAllowedClient() {
        when(config.find(CLIENT_UID)).thenReturn(entry(true, false));
        IPorterApplication application = mock(IPorterApplication.class);
        when(application.asBinder()).thenReturn(mock(IBinder.class));
        Bundle args = new Bundle();
        args.putString(ATTACH_PACKAGE_NAME, PACKAGE);
        args.putInt(ATTACH_PROTOCOL_VERSION, PorterProtocol.VERSION);
        endpoint.attach(application, args);
    }

    /**
     * Forwards one parcel laid out as a Porter client writes it (strong binder, code, flags,
     * payload) and reports what the target saw: {@code {flags, first int of the forwarded payload}}.
     */
    private int[] forward() throws Exception {
        int[] seen = new int[2];
        IBinder target = mock(IBinder.class);
        when(target.transact(anyInt(), any(Parcel.class), any(), anyInt())).thenAnswer(invocation -> {
            seen[0] = invocation.getArgument(3);
            Parcel forwarded = invocation.getArgument(1);
            forwarded.setDataPosition(0);
            seen[1] = forwarded.readInt();
            return true;
        });

        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(PorterProtocol.DESCRIPTOR);
            data.writeStrongBinder(target);
            data.writeInt(TARGET_CODE);
            data.writeInt(IN_PARCEL_FLAGS);
            data.writeInt(PAYLOAD);
            data.setDataPosition(0);
            assertTrue(endpoint.onTransact(PorterProtocol.TRANSACTION_transactRemote, data, reply, OUTER_FLAGS));
        } finally {
            data.recycle();
            reply.recycle();
        }
        verify(target).transact(eq(TARGET_CODE), any(Parcel.class), any(), anyInt());
        return seen;
    }

    @Test
    public void transactRemoteForwardsWithTheInParcelFlags() throws Exception {
        attachAllowedClient();

        assertArrayEquals(new int[]{IN_PARCEL_FLAGS, PAYLOAD}, forward());
    }

    @Test
    public void transactRemoteIsBoundToThePorterToken() throws Exception {
        attachAllowedClient();

        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(ShizukuApiConstants.BINDER_DESCRIPTOR);
            data.writeStrongBinder(mock(IBinder.class));
            data.writeInt(TARGET_CODE);
            data.setDataPosition(0);

            assertThrows(SecurityException.class,
                    () -> endpoint.onTransact(PorterProtocol.TRANSACTION_transactRemote, data, reply, 0));
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    @Test
    public void thePorterEndpointOwnsItsOwnShellCodes() throws Exception {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(PorterProtocol.DESCRIPTOR);
            data.setDataPosition(0);

            assertThrows(SecurityException.class,
                    () -> endpoint.onTransact(PorterProtocol.TRANSACTION_PORSH_BASE, data, reply, 0));

            data.setDataPosition(0);
            assertFalse(endpoint.onTransact(LEGACY_PORSH_BASE, data, reply, 0));
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    @Test
    public void theShizukuEndpointOwnsItsOwnShellCodes() throws Exception {
        giveTheLegacyServiceItsShell();

        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(ShizukuApiConstants.BINDER_DESCRIPTOR);
            data.setDataPosition(0);

            assertFalse(service.onTransact(PorterProtocol.TRANSACTION_PORSH_BASE, data, reply, 0));

            data.setDataPosition(0);
            assertThrows(SecurityException.class,
                    () -> service.onTransact(LEGACY_PORSH_BASE, data, reply, 0));
        } finally {
            data.recycle();
            reply.recycle();
        }
    }
}
