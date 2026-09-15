package eu.darken.porter.core;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static rikka.shizuku.server.ServerTestSupport.application;
import static rikka.shizuku.server.ServerTestSupport.entry;
import static rikka.shizuku.server.ServerTestSupport.newCore;

import android.os.Handler;
import android.os.IBinder;
import android.system.Os;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Collections;

import rikka.shizuku.server.ClientManager;
import rikka.shizuku.server.ConfigManager;
import rikka.shizuku.server.ServerTestSupport.TestPolicy;
import rikka.shizuku.server.ServerTestSupport.TestUserServiceManager;
import rikka.shizuku.server.UserServiceManager;
import rikka.shizuku.server.util.HandlerUtil;
import rikka.shizuku.server.util.OsUtils;

/**
 * Who {@link PorterCore#enforceCallingPermission} and {@link PorterCore#enforceManagerPermission}
 * admit, and with which message they refuse everyone else.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class PorterCoreGateTest {

    static final int CLIENT_UID = 10200;
    static final int CLIENT_PID = 45678;

    private static final String PACKAGE = "eu.darken.porter.probe";

    private PorterCore<UserServiceManager, ClientManager<ConfigManager>, ConfigManager> core;
    private TestPolicy policy;
    private ConfigManager config;
    private ClientManager<ConfigManager> clients;
    private CallerIdentity caller;

    @Before
    public void setup() {
        HandlerUtil.setMainHandler(mock(Handler.class));
        config = mock(ConfigManager.class);
        clients = new ClientManager<>(config);
        policy = new TestPolicy();
        core = newCore(clients, new TestUserServiceManager(), config, policy,
                uid -> Collections.singletonList(PACKAGE));

        caller = new CallerIdentity(CLIENT_UID, CLIENT_PID);
    }

    private void attach(int apiVersion) {
        clients.addClient(CLIENT_UID, CLIENT_PID, application(mock(IBinder.class)), PACKAGE, apiVersion);
    }

    @Test
    public void serverUidPassesWithoutARecord() {
        core.enforceCallingPermission("getVersion", new CallerIdentity(OsUtils.getUid(), CLIENT_PID));
    }

    @Test
    public void unattachedCallerIsRefusedAsNotAttached() {
        SecurityException e = assertThrows(SecurityException.class,
                () -> core.enforceCallingPermission("getVersion", caller));

        assertTrue(e.getMessage(), e.getMessage().contains("is not an attached client"));
    }

    @Test
    public void attachedButDisallowedClientIsRefusedAsRequiresPermission() {
        when(config.find(CLIENT_UID)).thenReturn(entry(false, false));
        attach(13);

        SecurityException e = assertThrows(SecurityException.class,
                () -> core.enforceCallingPermission("getVersion", caller));

        assertTrue(e.getMessage(), e.getMessage().contains("requires permission"));
    }

    @Test
    public void attachedAllowedClientPasses() {
        when(config.find(CLIENT_UID)).thenReturn(entry(true, false));
        attach(13);

        core.enforceCallingPermission("getVersion", caller);
    }

    @Test
    public void overrideAnsweringTrueAdmitsAnUnattachedCaller() {
        policy.callerPermission = true;

        core.enforceCallingPermission("transactRemote", caller);
    }

    @Test
    public void managerGatePassesForOwnPid() {
        core.enforceManagerPermission("exit", new CallerIdentity(CLIENT_UID, Os.getpid()));
    }

    @Test
    public void managerGatePassesWhenOverrideAgrees() {
        policy.managerPermission = true;

        core.enforceManagerPermission("exit", caller);
    }

    @Test
    public void managerGateRefusesOtherwise() {
        SecurityException e = assertThrows(SecurityException.class,
                () -> core.enforceManagerPermission("exit", caller));

        assertTrue(e.getMessage(), e.getMessage().contains("is not manager"));
    }
}
