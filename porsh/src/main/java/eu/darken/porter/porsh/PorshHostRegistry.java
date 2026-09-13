package eu.darken.porter.porsh;

import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps one shell host per calling pid. Every method here runs on a binder thread.
 */
class PorshHostRegistry {

    private static final String TAG = "PorshHostRegistry";

    static final long EXIT_CODE_TIMEOUT_MILLIS = 5_000;

    /**
     * How long an exited host is kept so its client can still collect the status.
     */
    static final long UNCOLLECTED_MAX_AGE_MILLIS = 60_000;

    interface HostFactory {
        PorshHost create(
                String[] args, String[] env, String dir,
                byte tty,
                ParcelFileDescriptor stdin, ParcelFileDescriptor stdout, ParcelFileDescriptor stderr);
    }

    interface Clock {
        long elapsedMillis();
    }

    private final ConcurrentHashMap<Integer, PorshHost> hosts = new ConcurrentHashMap<>();
    private final HostFactory factory;
    private final Clock clock;

    PorshHostRegistry(HostFactory factory, Clock clock) {
        this.factory = factory;
        this.clock = clock;
    }

    void createHost(
            int callingPid,
            String[] args, String[] env, String dir,
            byte tty,
            ParcelFileDescriptor stdin, ParcelFileDescriptor stdout, ParcelFileDescriptor stderr) {

        reapUncollected();

        PorshHost host = factory.create(args, env, dir, tty, stdin, stdout, stderr);
        host.start();
        Log.d(TAG, "Forked " + host.getPid());

        hosts.put(callingPid, host);
    }

    void setWindowSize(int callingPid, long size) {
        PorshHost host = hosts.get(callingPid);
        if (host == null) {
            Log.d(TAG, "Not existing host created by " + callingPid);
            return;
        }

        host.setWindowSize(size);
    }

    int getExitCode(int callingPid) {
        PorshHost host = hosts.get(callingPid);
        if (host == null) {
            Log.d(TAG, "Not existing host created by " + callingPid);
            return -1;
        }

        int exitCode = host.awaitExitCode(EXIT_CODE_TIMEOUT_MILLIS);
        if (!host.hasExited()) {
            return exitCode;
        }
        // Conditional: another transaction from the same pid may already have
        // installed a newer host while this one was waiting.
        hosts.remove(callingPid, host);
        return host.getExitCode();
    }

    /**
     * Drops hosts whose client never came back for the exit status. An entry that has
     * merely exited is not abandoned: its client drains output first and asks afterwards.
     */
    private void reapUncollected() {
        long now = clock.elapsedMillis();
        for (Map.Entry<Integer, PorshHost> entry : hosts.entrySet()) {
            PorshHost host = entry.getValue();
            if (!host.hasExited()) {
                continue;
            }
            if (now - host.getExitedAtMillis() <= UNCOLLECTED_MAX_AGE_MILLIS) {
                continue;
            }
            if (hosts.remove(entry.getKey(), host)) {
                Log.d(TAG, "Reaped uncollected host of " + entry.getKey());
            }
        }
    }
}
