package eu.darken.porter.sdk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import android.content.ComponentName;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import eu.darken.porter.server.IPorterServiceConnection;

/** The binder identity a user service binding presents to the server. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class PorterProtocolWireUserServiceTest {

    private static final String PACKAGE = "eu.darken.porter.probe";
    private static final String CLASS = "ProbeService";
    private static final String PROCESS_SUFFIX = "probe";

    /** Records the connection binder of every add and remove the server was asked for. */
    private static class RecordingService extends FakePorterService {

        final List<IBinder> connections = Collections.synchronizedList(new ArrayList<>());

        @Override
        public int addUserService(IPorterServiceConnection conn, Bundle args) {
            connections.add(conn == null ? null : conn.asBinder());
            return super.addUserService(conn, args);
        }

        @Override
        public int removeUserService(IPorterServiceConnection conn, Bundle args) {
            connections.add(conn == null ? null : conn.asBinder());
            return super.removeUserService(conn, args);
        }
    }

    /**
     * Holds its first lookup until the other thread reaches one too, so a wire that looks up and
     * registers without a lock creates two stubs for the one callback.
     */
    private static final class GatedCallback implements UserServiceCallback {

        private final Map<PorterBackend, IBinder> registered = new ConcurrentHashMap<>();
        private final CountDownLatch lookups = new CountDownLatch(2);

        @Nullable
        @Override
        public IBinder registeredBinder(@NonNull PorterBackend backend) {
            lookups.countDown();
            try {
                lookups.await(200, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return registered.get(backend);
        }

        @Override
        public void rememberRegisteredBinder(@NonNull PorterBackend backend, @NonNull IBinder binder) {
            registered.put(backend, binder);
        }

        @Override
        public void connected(@NonNull IBinder binder) {
        }

        @Override
        public void died() {
        }
    }

    private static final ServiceConnection NO_OP = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
        }
    };

    @After
    public void teardown() {
        Porter.resetForTest();
    }

    private static RecordingService attached() {
        RecordingService fake = new RecordingService();
        Porter.onBinderReceived(fake, PACKAGE);
        return fake;
    }

    private static Porter.UserServiceArgs args(String tag) {
        return new Porter.UserServiceArgs(new ComponentName(PACKAGE, CLASS))
                .processNameSuffix(PROCESS_SUFFIX)
                .tag(tag);
    }

    @Test
    public void oneBindingPresentsOneBinderAcrossAddAndRemove() {
        RecordingService fake = attached();
        Porter.UserServiceArgs args = args("one-binding");

        Porter.bindUserService(args, NO_OP);
        Porter.unbindUserService(args, NO_OP, false);

        assertEquals(2, fake.connections.size());
        assertNotNull(fake.connections.get(0));
        assertSame(fake.connections.get(0), fake.connections.get(1));
    }

    @Test
    public void twoBindingsPresentTwoBinders() {
        RecordingService fake = attached();

        Porter.bindUserService(args("first-binding"), NO_OP);
        Porter.bindUserService(args("second-binding"), NO_OP);

        assertEquals(2, fake.connections.size());
        assertNotNull(fake.connections.get(0));
        assertNotNull(fake.connections.get(1));
        assertNotSame(fake.connections.get(0), fake.connections.get(1));
    }

    @Test
    public void removingAndKillingNamesNoConnection() {
        RecordingService fake = attached();

        Porter.unbindUserService(args("killed-binding"), NO_OP, true);

        assertEquals(1, fake.connections.size());
        assertNull(fake.connections.get(0));
    }

    @Test
    public void twoThreadsBindingOneServiceRegisterOneBinder() throws Exception {
        RecordingService fake = attached();
        GatedCallback callback = new GatedCallback();
        Bundle add = args("concurrent-binding").forAdd();

        Runnable bind = () -> Porter.requireWire().addUserService(callback, add);
        Thread first = new Thread(bind);
        Thread second = new Thread(bind);
        first.start();
        second.start();
        first.join();
        second.join();

        assertEquals(2, fake.connections.size());
        assertNotNull(fake.connections.get(0));
        assertSame(fake.connections.get(0), fake.connections.get(1));
    }
}
