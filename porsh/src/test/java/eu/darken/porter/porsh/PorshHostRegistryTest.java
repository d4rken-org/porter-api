package eu.darken.porter.porsh;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class PorshHostRegistryTest {

    private final ConcurrentHashMap<Integer, FakeHost> created = new ConcurrentHashMap<>();
    private final AtomicLong now = new AtomicLong(0);

    private final PorshHostRegistry registry = new PorshHostRegistry(
            (args, env, dir, tty, stdin, stdout, stderr) -> {
                int pid = Integer.parseInt(args[0]);
                FakeHost host = new FakeHost(pid);
                created.put(pid, host);
                return host;
            },
            now::get);

    /**
     * The registry keys by calling pid while the factory only sees the argv, so the pid
     * rides along in argv[0] to let a test tie a created host back to its client.
     */
    private void createHost(int pid) {
        registry.createHost(pid, new String[]{String.valueOf(pid)}, null, null, (byte) 0, null, null, null);
    }

    @Test
    public void concurrentCreateHost_keepsEveryHostReachableByItsPid() throws Exception {
        int clients = 64;
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(clients);
        List<Thread> threads = new ArrayList<>();

        for (int i = 0; i < clients; i++) {
            int pid = 1000 + i;
            Thread thread = new Thread(() -> {
                try {
                    go.await();
                    createHost(pid);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
            threads.add(thread);
            thread.start();
        }

        go.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        for (Thread thread : threads) {
            thread.join();
        }

        assertEquals(clients, created.size());
        for (int i = 0; i < clients; i++) {
            int pid = 1000 + i;
            registry.setWindowSize(pid, pid);
            assertEquals(pid, created.get(pid).windowSize);
        }
    }

    @Test
    public void createHost_replacesTheExistingHostOfTheSamePid() {
        createHost(100);
        FakeHost first = created.get(100);

        createHost(100);
        FakeHost second = created.get(100);

        registry.setWindowSize(100, 42);

        assertEquals(-1, first.windowSize);
        assertEquals(42, second.windowSize);
    }

    @Test
    public void anotherClientStarting_doesNotDiscardAnExitedButUncollectedHost() {
        createHost(100);
        created.get(100).onExited(7);

        createHost(200);

        assertEquals(7, registry.getExitCode(100));
    }

    @Test
    public void collectionRemovesConditionally_soANewerHostOfTheSamePidSurvives() throws Exception {
        createHost(100);
        FakeHost first = created.get(100);

        AtomicInteger collected = new AtomicInteger(Integer.MIN_VALUE);
        Thread collector = new Thread(() -> collected.set(registry.getExitCode(100)));
        collector.start();

        // The collector now holds the first host and is parked on its latch. Install a
        // replacement under the same pid before releasing it.
        assertTrue(first.awaitEntered.await(10, TimeUnit.SECONDS));
        createHost(100);
        FakeHost second = created.get(100);
        first.onExited(7);

        collector.join(TimeUnit.SECONDS.toMillis(10));
        assertFalse(collector.isAlive());
        assertEquals(7, collected.get());

        registry.setWindowSize(100, 42);
        assertEquals(42, second.windowSize);
    }

    @Test
    public void createHost_reapsExitedHostsOnlyOnceTheyAreStale() {
        createHost(100);
        FakeHost stale = created.get(100);
        stale.onExited(7);
        stale.exitedAt = 0;

        createHost(200);
        FakeHost recent = created.get(200);
        recent.onExited(8);
        recent.exitedAt = 2_000;

        now.set(61_000);
        createHost(300);

        assertEquals(-1, registry.getExitCode(100));
        assertEquals(8, registry.getExitCode(200));
    }

    @Test
    public void createHost_doesNotWaitOnStillRunningHostsOfOtherClients() {
        for (int i = 0; i < 8; i++) {
            createHost(1000 + i);
        }

        long startedAt = System.nanoTime();
        createHost(2000);
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

        assertTrue(
                "createHost took " + elapsedMillis + "ms, so it blocked on a running host",
                elapsedMillis < PorshHostRegistry.EXIT_CODE_TIMEOUT_MILLIS);
    }

    @Test
    public void getExitCode_timingOutDoesNotEvictTheEntry() {
        FakeHost host = new FakeHost(100) {
            @Override
            int awaitExitCode(long timeoutMillis) {
                return -1;
            }
        };
        PorshHostRegistry registry = new PorshHostRegistry(
                (args, env, dir, tty, stdin, stdout, stderr) -> host,
                now::get);
        registry.createHost(100, new String[]{"100"}, null, null, (byte) 0, null, null, null);

        assertEquals(-1, registry.getExitCode(100));

        registry.setWindowSize(100, 42);
        assertEquals(42, host.windowSize);
    }

    @Test
    public void getExitCode_exitPublishedAfterTheWaitTimedOut_reportsItToTheSameCall() {
        FakeHost host = new FakeHost(100) {
            @Override
            int awaitExitCode(long timeoutMillis) {
                // The racing window: the timed wait has given up, and the host's exit is
                // published before the registry gets to read hasExited().
                onExited(7);
                return -1;
            }
        };
        PorshHostRegistry registry = new PorshHostRegistry(
                (args, env, dir, tty, stdin, stdout, stderr) -> host,
                now::get);
        registry.createHost(100, new String[]{"100"}, null, null, (byte) 0, null, null, null);

        assertEquals(
                "the status published during the wait window was reported as a timeout",
                7,
                registry.getExitCode(100));

        registry.setWindowSize(100, 42);
        assertEquals("the collected host was left in the registry", -1, host.windowSize);
    }
}
