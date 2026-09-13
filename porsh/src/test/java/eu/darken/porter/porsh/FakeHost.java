package eu.darken.porter.porsh;

import java.util.concurrent.CountDownLatch;

/**
 * A {@link PorshHost} that never forks. {@code start()} is the only member that reaches
 * JNI, so overriding it is enough to exercise the registry off-device.
 */
class FakeHost extends PorshHost {

    private final int pid;

    /** Opens once a thread has entered awaitExitCode on this host. */
    final CountDownLatch awaitEntered = new CountDownLatch(1);

    volatile long windowSize = -1;
    volatile long exitedAt;

    FakeHost(int pid) {
        super(new String[0], null, null, (byte) 0, null, null, null);
        this.pid = pid;
    }

    @Override
    public void start() {
    }

    @Override
    public int getPid() {
        return pid;
    }

    @Override
    public void setWindowSize(long size) {
        windowSize = size;
    }

    @Override
    long getExitedAtMillis() {
        return exitedAt;
    }

    @Override
    int awaitExitCode(long timeoutMillis) {
        awaitEntered.countDown();
        return super.awaitExitCode(timeoutMillis);
    }
}
