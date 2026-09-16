package eu.darken.porter.sdk;

import static eu.darken.porter.protocol.PorterProtocol.PERMISSION_RESULT_ALLOWED;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.os.Bundle;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Listeners that register or unregister one from inside their own callback. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class PorterReentrancyTest {

    private static final String PACKAGE = "eu.darken.porter.probe";

    @After
    public void teardown() {
        Porter.resetForTest();
    }

    @Test
    public void aOneShotReceivedListenerCanRemoveItselfWhileItIsCalled() {
        int[] calls = {0};
        Porter.addBinderReceivedListener(new Porter.OnBinderReceivedListener() {
            @Override
            public void onBinderReceived() {
                calls[0]++;
                Porter.removeBinderReceivedListener(this);
            }
        });

        FakePorterService fake = new FakePorterService();
        Porter.onBinderReceived(fake, PACKAGE);

        assertEquals(1, calls[0]);
        assertEquals("the listener list is not the attach", 1, fake.attachCount);
        assertTrue("a listener must not be able to drop the connection", Porter.pingBinder());
    }

    @Test
    public void aReceivedListenerCanRegisterAnotherWhileItIsCalled() {
        int[] calls = {0};
        Porter.addBinderReceivedListener(() -> {
            calls[0]++;
            Porter.addBinderReceivedListener(() -> calls[0]++);
        });

        FakePorterService fake = new FakePorterService();
        Porter.onBinderReceived(fake, PACKAGE);

        assertEquals(1, calls[0]);
        assertTrue(Porter.pingBinder());
    }

    @Test
    public void aOneShotDeadListenerCanRemoveItselfWhileItIsCalled() {
        int[] calls = {0};
        Porter.addBinderDeadListener(new Porter.OnBinderDeadListener() {
            @Override
            public void onBinderDead() {
                calls[0]++;
                Porter.removeBinderDeadListener(this);
            }
        });
        Porter.onBinderReceived(new FakePorterService(), PACKAGE);

        Porter.onBinderReceived(null, null);

        assertEquals(1, calls[0]);
    }

    @Test
    public void aOneShotPermissionResultListenerCanRemoveItselfWhileItIsCalled() throws Exception {
        FakePorterService fake = new FakePorterService();
        Porter.onBinderReceived(fake, PACKAGE);
        int[] calls = {0};
        Porter.addRequestPermissionResultListener(new Porter.OnRequestPermissionResultListener() {
            @Override
            public void onRequestPermissionResult(int requestCode, int grantResult) {
                calls[0]++;
                Porter.removeRequestPermissionResultListener(this);
            }
        });

        Bundle data = new Bundle();
        data.putBoolean(PERMISSION_RESULT_ALLOWED, true);
        fake.application.dispatchRequestPermissionResult(7, data);

        assertEquals(1, calls[0]);
    }
}
