package eu.darken.porter.core;

import android.os.IBinder;
import android.os.ParcelFileDescriptor;

import androidx.annotation.Nullable;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import rikka.shizuku.server.util.Logger;
import rikka.shizuku.server.util.ParcelFileDescriptorUtil;

/**
 * A process the server spawned on someone's behalf, and the pipes handed back for it.
 *
 * <p>{@code getOutputStream} and {@code getInputStream} answer with the same descriptor every time;
 * {@code getErrorStream} opens a fresh pipe and a fresh transfer thread on each call, so two callers
 * end up competing for the same bytes.
 */
public final class ServerProcess {

    private static final Logger LOGGER = new Logger("ServerProcess");

    private final Process process;
    private ParcelFileDescriptor in;
    private ParcelFileDescriptor out;

    public ServerProcess(Process process, @Nullable IBinder ownerToken) {
        this.process = process;

        if (ownerToken != null) {
            try {
                IBinder.DeathRecipient deathRecipient = () -> {
                    try {
                        if (alive()) {
                            destroy();
                            LOGGER.i("destroy process because the owner is dead");
                        }
                    } catch (Throwable e) {
                        LOGGER.w(e, "failed to destroy process");
                    }
                };
                ownerToken.linkToDeath(deathRecipient, 0);
            } catch (Throwable e) {
                LOGGER.w(e, "linkToDeath");
            }
        }
    }

    public ParcelFileDescriptor getOutputStream() {
        if (out == null) {
            try {
                out = ParcelFileDescriptorUtil.pipeTo(process.getOutputStream());
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
        return out;
    }

    public ParcelFileDescriptor getInputStream() {
        if (in == null) {
            try {
                in = ParcelFileDescriptorUtil.pipeFrom(process.getInputStream());
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
        return in;
    }

    public ParcelFileDescriptor getErrorStream() {
        try {
            return ParcelFileDescriptorUtil.pipeFrom(process.getErrorStream());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public int waitFor() {
        try {
            return process.waitFor();
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    public int exitValue() {
        return process.exitValue();
    }

    public void destroy() {
        process.destroy();
    }

    public boolean alive() {
        try {
            this.exitValue();
            return false;
        } catch (IllegalThreadStateException e) {
            return true;
        }
    }

    public boolean waitForTimeout(long timeout, TimeUnit unit) {
        long startTime = System.nanoTime();
        long rem = unit.toNanos(timeout);

        do {
            try {
                exitValue();
                return true;
            } catch (IllegalThreadStateException ex) {
                if (rem > 0) {
                    try {
                        Thread.sleep(
                                Math.min(TimeUnit.NANOSECONDS.toMillis(rem) + 1, 100));
                    } catch (InterruptedException e) {
                        throw new IllegalStateException();
                    }
                }
            }
            rem = unit.toNanos(timeout) - (System.nanoTime() - startTime);
        } while (rem > 0);
        return false;
    }
}
