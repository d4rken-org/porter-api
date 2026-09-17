package eu.darken.porter.sdk;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** What a started process is given, and what the caller gets back for it. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class PorterNewProcessTest {

    private static final String PACKAGE = "eu.darken.porter";

    private FakePorterService fake;

    @Before
    public void setup() {
        fake = new FakePorterService();
        Porter.onBinderReceived(fake, PACKAGE);
    }

    @After
    public void teardown() {
        Porter.resetForTest();
    }

    @Test
    public void theCommandEnvironmentAndDirectoryReachTheServerAsGiven() {
        PorterRemoteProcess process =
                Porter.newProcess(new String[]{"id"}, new String[]{"K=V"}, "/");

        assertArrayEquals(new String[]{"id"}, fake.newProcessCmd);
        assertArrayEquals(new String[]{"K=V"}, fake.newProcessEnv);
        assertEquals("/", fake.newProcessDir);
        assertSame(fake.remoteProcess.asBinder(), process.asBinder());
    }
}
