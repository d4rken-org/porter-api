package rikka.shizuku.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static rikka.shizuku.server.ServerTestSupport.newService;

import android.os.Bundle;
import android.os.IBinder;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowBinder;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import moe.shizuku.server.IShizukuApplication;
import moe.shizuku.server.IShizukuServiceConnection;
import rikka.shizuku.server.ServerTestSupport.TestService;
import rikka.shizuku.server.ServerTestSupport.TestUserServiceManager;

/**
 * The shape the consuming app reaches through. {@code ServiceAuthorizationTest},
 * {@code UserServiceBindIdentityTest} and {@code ApkReconcilerTest} in {@code d4rken-org/porter}
 * bind to these members by name, by type and by reflection.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class FacadeSurfaceTest {

    @After
    public void teardown() {
        ShadowBinder.reset();
    }

    private static Field field(Class<?> owner, String name) throws Exception {
        return owner.getDeclaredField(name);
    }

    @Test
    public void theServiceManagerFieldsCanBeInjectedByName() throws Exception {
        for (String name : new String[]{"clientManager", "userServiceManager", "configManager"}) {
            Field field = field(Service.class, name);
            assertTrue(name, Modifier.isPrivate(field.getModifiers()));
        }
    }

    @Test
    public void clientRecordKeepsTheApplicationFieldAndItsConstructor() throws Exception {
        Field client = field(ClientRecord.class, "client");
        assertTrue(Modifier.isPublic(client.getModifiers()));
        assertEquals(IShizukuApplication.class, client.getType());

        Constructor<ClientRecord> constructor = ClientRecord.class.getDeclaredConstructor(
                int.class, int.class, IShizukuApplication.class, String.class, int.class);
        assertTrue(Modifier.isPublic(constructor.getModifiers()));
    }

    @Test
    public void addClientStaysPublicAndOverridable() throws Exception {
        Method addClient = ClientManager.class.getDeclaredMethod(
                "addClient", int.class, int.class, IShizukuApplication.class, String.class, int.class);

        assertTrue(Modifier.isPublic(addClient.getModifiers()));
        assertFalse(Modifier.isFinal(addClient.getModifiers()));
    }

    @Test
    public void theUserServiceManagerBundleEntryPointsStayPublic() throws Exception {
        Method[] methods = {
                UserServiceManager.class.getDeclaredMethod(
                        "addUserService", IShizukuServiceConnection.class, Bundle.class, int.class),
                UserServiceManager.class.getDeclaredMethod(
                        "removeUserService", IShizukuServiceConnection.class, Bundle.class),
                UserServiceManager.class.getDeclaredMethod("attachUserService", IBinder.class, Bundle.class),
                UserServiceManager.class.getDeclaredMethod(
                        "attachUserService", IBinder.class, Bundle.class, String.class),
                Service.class.getDeclaredMethod("attachUserService", IBinder.class, Bundle.class, String.class),
        };

        for (Method method : methods) {
            assertTrue(method.getName(), Modifier.isPublic(method.getModifiers()));
        }
    }

    @Test
    public void theStartCommandHookKeepsItsNineParameters() throws Exception {
        Method startCmd = UserServiceManager.class.getDeclaredMethod(
                "getUserServiceStartCmd", UserServiceRecord.class, String.class, String.class, String.class,
                String.class, String.class, int.class, boolean.class, boolean.class);

        assertTrue(Modifier.isPublic(startCmd.getModifiers()));
        assertTrue(Modifier.isAbstract(startCmd.getModifiers()));
    }

    @Test
    public void theUserServiceRecordConstructorStaysPublic() throws Exception {
        Constructor<UserServiceRecord> constructor =
                UserServiceRecord.class.getDeclaredConstructor(int.class, boolean.class);

        assertTrue(Modifier.isPublic(constructor.getModifiers()));
    }

    @Test
    public void aServiceBuiltWithoutItsConstructorStillGatesCallers() {
        ConfigManager config = mock(ConfigManager.class);
        TestService service = newService(new ClientManager<>(config), new TestUserServiceManager(), config);
        ShadowBinder.setCallingUid(10200);
        ShadowBinder.setCallingPid(45678);

        assertThrows(SecurityException.class, () -> service.enforceCallingPermission("getVersion"));
    }
}
