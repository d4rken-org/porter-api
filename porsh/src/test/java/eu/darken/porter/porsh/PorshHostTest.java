package eu.darken.porter.porsh;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class PorshHostTest {

    @Test
    public void awaitExitCode_returnsAStatusPublishedAfterTheReaderIsWaiting() throws Exception {
        FakeHost host = new FakeHost(100);

        AtomicInteger observed = new AtomicInteger(Integer.MIN_VALUE);
        Thread reader = new Thread(() -> observed.set(host.awaitExitCode(10_000)));
        reader.start();

        assertTrue(host.awaitEntered.await(10, TimeUnit.SECONDS));
        host.onExited(3);

        reader.join(TimeUnit.SECONDS.toMillis(10));
        assertFalse(reader.isAlive());
        assertEquals(3, observed.get());
    }

    @Test
    public void awaitExitCode_returnsMinusOneWhenTheProcessOutlivesTheTimeout() {
        FakeHost host = new FakeHost(100);

        assertEquals(-1, host.awaitExitCode(50));
    }

    @Test
    public void hasExited_staysFalseUntilTheWaiterPublishes() {
        FakeHost host = new FakeHost(100);

        assertFalse(host.hasExited());

        host.onExited(0);

        assertTrue(host.hasExited());
    }
}
