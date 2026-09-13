package eu.darken.porter.client;

import android.os.IBinder;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import rikka.shizuku.Shizuku;

import java.lang.reflect.Field;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * A cached grant belongs to one connection. Reporting it after that connection is gone tells an
 * app it is authorized when it is not.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class CachedPermissionTest {
    private static final String[] STATE = {
            "binder", "service", "permissionGranted", "shouldShowRequestPermissionRationale",
            "preV11", "binderReady", "serverUid", "serverApiVersion"
    };

    @Before public void reset() throws Exception {
        set("binder", null);
        set("service", null);
        set("permissionGranted", false);
        set("shouldShowRequestPermissionRationale", false);
        set("preV11", false);
        set("binderReady", false);
    }

    /** Other test classes in this module share the JVM and the same statics. */
    @After public void clear() throws Exception {
        for (String name : STATE) {
            Field field = field(name);
            Class<?> type = field.getType();
            if (type == boolean.class) field.set(null, false);
            else if (type == int.class) field.set(null, -1);
            else field.set(null, null);
        }
    }

    @Test public void aDeadBinderDropsTheGrantItReported() throws Exception {
        set("binder", mock(IBinder.class));
        set("permissionGranted", true);

        Shizuku.onBinderReceived(null, "test.app");

        assertFalse("the connection that granted access is gone", granted());
    }

    @Test public void aNewBinderDoesNotInheritThePreviousGrant() throws Exception {
        set("permissionGranted", true);

        // Attaching fails against a mock transport, which is the point: the grant must already be
        // cleared before the server has had a chance to report one for this connection.
        Shizuku.onBinderReceived(mock(IBinder.class), "test.app");

        assertFalse("no grant has been reported for this connection yet", granted());
    }

    @Test public void redeliveringTheSameBinderKeepsTheGrant() throws Exception {
        IBinder same = mock(IBinder.class);
        set("binder", same);
        set("permissionGranted", true);

        Shizuku.onBinderReceived(same, "test.app");

        assertTrue("the connection did not change", granted());
    }

    private static boolean granted() throws Exception {
        return (boolean) field("permissionGranted").get(null);
    }

    private static void set(String name, Object value) throws Exception {
        field(name).set(null, value);
    }

    private static Field field(String name) throws Exception {
        Field field = Shizuku.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
