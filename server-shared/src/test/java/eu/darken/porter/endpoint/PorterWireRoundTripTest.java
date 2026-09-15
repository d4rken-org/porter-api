package eu.darken.porter.endpoint;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static rikka.shizuku.server.ServerTestSupport.entry;
import static rikka.shizuku.server.ServerTestSupport.newService;

import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowBinder;
import org.robolectric.shadows.ShadowLooper;

import java.util.Collections;

import eu.darken.porter.core.UserServiceOptions;
import eu.darken.porter.sdk.Porter;
import rikka.shizuku.server.ClientManager;
import rikka.shizuku.server.ConfigManager;
import rikka.shizuku.server.ServerTestSupport.TestService;
import rikka.shizuku.server.ServerTestSupport.TestUserServiceManager;
import rikka.shizuku.server.util.HandlerUtil;
import rikka.shizuku.server.util.OsUtils;

/**
 * The SDK talking to the endpoint in one process: what one side writes, the other reads. Both
 * halves of the wire are pinned here, so a key renamed on one side alone fails.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class PorterWireRoundTripTest {

    private static final String PACKAGE = "eu.darken.porter.probe";
    private static final String CLASS = "ProbeService";
    /** User 10, app id 10200, so the user id derived from it is not the trivial zero. */
    private static final int CLIENT_UID = 1010200;
    private static final int CLIENT_PID = 45678;

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
        Porter.resetForTest();
        ShadowBinder.reset();
    }

    @Test
    public void anAllowedClientAttachesAndSeesTheServer() {
        when(config.find(CLIENT_UID)).thenReturn(entry(true, false));

        Porter.onBinderReceived(endpoint, PACKAGE);

        assertTrue(Porter.pingBinder());
        assertEquals(OsUtils.getUid(), Porter.getUid());
        assertEquals(1, Porter.getServerProtocolVersion());
        assertEquals(PackageManager.PERMISSION_GRANTED, Porter.checkSelfPermission());
    }

    @Test
    public void aClientWithoutAGrantIsToldSo() {
        Porter.onBinderReceived(endpoint, PACKAGE);

        assertEquals(PackageManager.PERMISSION_DENIED, Porter.checkSelfPermission());
    }

    @Test
    public void aPermissionResultComesBackThroughTheCallback() {
        when(config.find(CLIENT_UID)).thenReturn(entry(true, false));
        Porter.onBinderReceived(endpoint, PACKAGE);
        int[] seen = {-1, -1};
        Porter.addRequestPermissionResultListener((requestCode, grantResult) -> {
            seen[0] = requestCode;
            seen[1] = grantResult;
        });

        Porter.requestPermission(7);
        ShadowLooper.idleMainLooper();

        assertArrayEquals(new int[]{7, PackageManager.PERMISSION_GRANTED}, seen);
    }

    @Test
    public void theUserServiceBundlesSurviveTheRoundTrip() {
        ComponentName component = new ComponentName(PACKAGE, CLASS);
        Bundle add = new Porter.UserServiceArgs(component)
                .tag("t").version(3).daemon(false).processNameSuffix("p").debuggable(true).forAdd();

        UserServiceOptions bind = PorterUserServiceOptions.decodeForBind(add);

        assertEquals(component, bind.component);
        assertEquals("t", bind.tag);
        assertEquals(3, bind.versionCode);
        assertEquals("p", bind.processNameSuffix);
        assertFalse(bind.daemon);
        assertTrue(bind.debuggable);
        assertFalse(bind.noCreate);
        assertFalse(bind.use32Bit);
        assertTrue(bind.remove);

        Bundle remove = new Porter.UserServiceArgs(component).tag("t").forRemove(false);

        assertFalse(PorterUserServiceOptions.decodeForRemove(remove).remove);
    }
}
