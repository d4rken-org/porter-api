package eu.darken.porter.sdk;

import static org.junit.Assert.assertEquals;

import android.os.Bundle;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import eu.darken.porter.server.IPorterApplication;

/** When a sticky listener catches up on its own and when the dispatch is what calls it. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class PorterReadinessTest {

    private static final String PACKAGE = "eu.darken.porter.probe";

    /** Registers a sticky listener from inside attach, while the connection is neither yet nor no longer. */
    private static class RegisteringService extends FakePorterService {

        final int[] calls = {0};

        @Override
        public Bundle attach(IPorterApplication application, Bundle args) {
            Porter.addBinderReceivedListenerSticky(() -> calls[0]++);
            return super.attach(application, args);
        }
    }

    @After
    public void teardown() {
        Porter.resetForTest();
    }

    @Test
    public void aStickyListenerRegisteredDuringAReplacingAttachIsCalledOnce() {
        Porter.onBinderReceived(new FakePorterService(), PACKAGE);

        RegisteringService replacement = new RegisteringService();
        Porter.onBinderReceived(replacement, PACKAGE);

        assertEquals(1, replacement.calls[0]);
    }

    @Test
    public void aStickyListenerRegisteredDuringTheFirstAttachIsCalledOnce() {
        RegisteringService first = new RegisteringService();

        Porter.onBinderReceived(first, PACKAGE);

        assertEquals(1, first.calls[0]);
    }

    @Test
    public void anAttachThatFailedLeavesNothingToCatchUpOn() {
        FakePorterService refused = new FakePorterService();
        refused.attachFailure = new SecurityException("not an attached client");
        Porter.onBinderReceived(refused, PACKAGE);

        int[] calls = {0};
        Porter.addBinderReceivedListenerSticky(() -> calls[0]++);

        assertEquals(0, calls[0]);
    }

    @Test
    public void aConnectionThatIsGoneLeavesNothingToCatchUpOn() {
        Porter.onBinderReceived(new FakePorterService(), PACKAGE);
        Porter.onBinderReceived(null, null);

        int[] calls = {0};
        Porter.addBinderReceivedListenerSticky(() -> calls[0]++);

        assertEquals(0, calls[0]);
    }
}
