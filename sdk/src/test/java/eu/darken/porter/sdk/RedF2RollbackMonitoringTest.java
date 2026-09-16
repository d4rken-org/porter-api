package eu.darken.porter.sdk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;

import android.os.IBinder;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowBinder;

import java.util.List;

/** A connection restored after a failed replacement must still be watched for death. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class RedF2RollbackMonitoringTest {

    private static final String PACKAGE = "eu.darken.porter.probe";

    @After
    public void teardown() {
        Porter.resetForTest();
    }

    @Test
    public void theConnectionARollbackRestoresIsStillWatchedForDeath() {
        FakePorterService serving = new FakePorterService();
        Porter.onBinderReceived(serving, PACKAGE);
        int[] dead = {0};
        Porter.addBinderDeadListener(() -> dead[0]++);

        FakePorterService refused = new FakePorterService();
        refused.attachFailure = new SecurityException("not an attached client");
        Porter.onBinderReceived(refused, PACKAGE);

        assertSame("the serving connection was not restored", serving, Porter.getBinder());

        ShadowBinder shadow = Shadow.extract(serving);
        List<IBinder.DeathRecipient> recipients = shadow.getDeathRecipients();
        assertEquals("the restored connection has no death recipient", 1, recipients.size());

        for (IBinder.DeathRecipient recipient : recipients) {
            recipient.binderDied();
        }

        assertEquals("the restored connection died unannounced", 1, dead[0]);
        assertFalse("a dead connection is still reported as live", Porter.pingBinder());
    }
}
