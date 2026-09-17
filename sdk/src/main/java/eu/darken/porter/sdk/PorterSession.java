package eu.darken.porter.sdk;

import static eu.darken.porter.protocol.PorterProtocol.CAPABILITIES_NONE;

import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * One connection to one server binder: what its attach reply said, what the server has pushed since,
 * and the two callbacks the server holds for it.
 *
 * <p>A session is never reused for another binder. A death notification, a permission push or an
 * attach reply that arrives late therefore carries the connection it belongs to, and can be told
 * apart from the connection that is current now.
 */
final class PorterSession {

    private static final String TAG = "Porter";

    /**
     * Guards {@link #current}, {@link #latest} and the death links. Taken inside the listener
     * monitor and outside a session's {@link #permissionLock}. Never held across attach, which is
     * the one call here that blocks in the server.
     */
    private static final Object SESSION_LOCK = new Object();

    /** The connection Porter answers for. */
    private static PorterSession current;
    /**
     * The newest connection that has not been given up on: {@link #current}, or the one attaching to
     * replace it. A session that is no longer this one has been superseded and stays silent.
     */
    private static PorterSession latest;
    /** Never reset, so a session of this process is never mistaken for a later one. */
    private static int connections;

    private final int generation;
    private final IBinder binder;
    private final PorterBackend backend;
    private final PorterWire wire;

    private int serverUid = -1;
    private int serverProtocolVersion = 0;
    private String serverContext = null;
    private long serverCapabilities = CAPABILITIES_NONE;

    /** Guarded by the listener monitor, which is where it is written and read. */
    private boolean ready = false;

    /**
     * Guards {@link #permissionGranted}, {@link #shouldShowRequestPermissionRationale} and
     * {@link #permissionStateGeneration}, which the server writes from a binder thread. Never held
     * across a binder call: acquire it to snapshot, release it for the call, acquire it to apply.
     */
    private final Object permissionLock = new Object();

    private boolean permissionGranted = false;
    private boolean shouldShowRequestPermissionRationale = false;
    /** Counts the state pushes, so a reply that started before one can tell it lost the race. */
    private int permissionStateGeneration = 0;

    private final IBinder.DeathRecipient deathRecipient = this::onBinderDied;
    /** Whether {@link #deathRecipient} is registered on {@link #binder}. Guarded by SESSION_LOCK. */
    private boolean linked = false;

    private final class SessionCallbacks implements PorterWire.Callbacks {

        @Override
        public void onRequestPermissionResult(int requestCode, boolean allowed) {
            Porter.scheduleRequestPermissionResultListener(PorterSession.this, requestCode,
                    allowed ? PackageManager.PERMISSION_GRANTED : PackageManager.PERMISSION_DENIED);
        }

        @Override
        public void onPermissionStateChanged(boolean granted, boolean shouldShowRationale) {
            synchronized (SESSION_LOCK) {
                // Both callbacks are oneway, so a server that has been replaced can still have one
                // in flight; it describes a connection nobody asks about any more. The connection
                // that is serving is one nobody asks about only once a replacement has published.
                if (current != PorterSession.this && latest != PorterSession.this) return;
                synchronized (permissionLock) {
                    permissionGranted = granted;
                    shouldShowRequestPermissionRationale = shouldShowRationale;
                    permissionStateGeneration++;
                }
            }
        }
    }

    private PorterSession(int generation, @NonNull IBinder binder, @NonNull PorterBackend backend) {
        this.generation = generation;
        this.binder = binder;
        this.backend = backend;
        this.wire = new PorterProtocolWire(binder, new SessionCallbacks());
    }

    static void onBinderReceived(@Nullable IBinder newBinder, String packageName,
                                 @NonNull PorterBackend backend) {
        if (newBinder == null) {
            dropCurrent();
            return;
        }

        if (backend != PorterBackend.PORTER) {
            Log.w(TAG, "binder from " + backend + " discarded: no wire speaks that backend yet");
            return;
        }

        PorterSession session;
        synchronized (SESSION_LOCK) {
            // The same binder delivered twice is the same connection. Attaching again would ask the
            // server for a second grant for it, link a second death recipient, and supersede a
            // replacement that is attaching, so the published connection counts as well as latest.
            if (current != null && current.binder == newBinder) return;
            if (latest != null && latest.binder == newBinder) return;

            session = new PorterSession(++connections, newBinder, backend);
            latest = session;
            session.link();
        }

        try {
            int pushes = session.permissionPushes();
            PorterWire.AttachReply reply = session.wire.attach(packageName);
            if (reply != null) session.apply(reply, pushes);

            boolean superseded;
            synchronized (SESSION_LOCK) {
                superseded = latest != session;
                if (!superseded) {
                    // The connection that is handing over stays watched until this one takes over,
                    // and both steps happen under the one lock: a death callback never sees a
                    // connection nobody watches, and an unlink that throws publishes nothing.
                    PorterSession previous = current;
                    if (previous != null && previous != session) previous.unlink();
                    current = session;
                }
            }
            if (superseded) {
                // A newer binder arrived while this one was attaching, and owns the connection now.
                session.unlink();
                return;
            }

            Log.i(TAG, "attached, connection " + session.generation);
            Porter.scheduleBinderReceivedListeners(session);
        } catch (RemoteException | RuntimeException e) {
            Log.w(TAG, Log.getStackTraceString(e));
            session.abandon();
        }
    }

    /** Gives up on this session without disturbing the connection that has replaced it. */
    private void abandon() {
        PorterSession retained;
        synchronized (SESSION_LOCK) {
            if (current == this) current = null;
            // Fall back to the published connection rather than to nothing: a newcomer that failed
            // must not silence the one that is still serving calls.
            if (latest == this) latest = current;
            retained = current;
        }
        unlink();
        // Whoever asked about the connection while this one was attaching was told there was none.
        if (retained != null) Porter.scheduleStickyCatchUp(retained);
    }

    private static void dropCurrent() {
        PorterSession dropped;
        synchronized (SESSION_LOCK) {
            dropped = current;
            current = null;
            // An attach still in flight is superseded too: the caller says there is no binder.
            latest = null;
            if (dropped != null) dropped.unlink();
        }
        if (dropped != null) Porter.scheduleBinderDeadListeners();
    }

    private void onBinderDied() {
        boolean wasCurrent;
        PorterSession retained = null;
        synchronized (SESSION_LOCK) {
            // The notification names no binder, so only the connection it was linked for may act on
            // it. One for a binder that has already been replaced tears nothing down.
            wasCurrent = current == this;
            if (wasCurrent) current = null;
            // A session that died while it was still attaching has nothing to publish any more, and
            // falls back to the connection that is serving, which may be one that outlives it.
            if (latest == this) {
                latest = current;
                retained = current;
            }
        }
        if (wasCurrent) Porter.scheduleBinderDeadListeners();
        // Whoever asked about the connection while this one was attaching was told there was none.
        if (retained != null) Porter.scheduleStickyCatchUp(retained);
    }

    private void link() {
        try {
            binder.linkToDeath(deathRecipient, 0);
            synchronized (SESSION_LOCK) {
                linked = true;
            }
        } catch (Throwable e) {
            Log.i(TAG, "linkToDeath");
        }
    }

    /**
     * Unlinks at most once: several paths give up on a session, and a binder in another process
     * throws when it is unlinked from a recipient it does not hold.
     */
    private void unlink() {
        synchronized (SESSION_LOCK) {
            if (!linked) return;
            linked = false;
            binder.unlinkToDeath(deathRecipient, 0);
        }
    }

    @Nullable
    static PorterSession current() {
        synchronized (SESSION_LOCK) {
            return current;
        }
    }

    @NonNull
    static PorterSession require() {
        PorterSession session = current();
        if (session == null) {
            throw new IllegalStateException("binder haven't been received");
        }
        return session;
    }

    @Nullable
    static IBinder currentBinder() {
        PorterSession session = current();
        return session == null ? null : session.binder;
    }

    /** The backend of the published connection, or null while there is none. */
    @Nullable
    static PorterBackend currentBackend() {
        PorterSession session = current();
        return session == null ? null : session.backend;
    }

    static boolean pingCurrent() {
        PorterSession session = current();
        return session != null && session.binder.pingBinder();
    }

    /**
     * Whether the newest connection has already told its listeners. The caller holds the listener
     * monitor, which is what {@link #ready} is guarded by.
     */
    static boolean latestIsReady() {
        synchronized (SESSION_LOCK) {
            return latest != null && latest.ready;
        }
    }

    /** The caller holds the listener monitor. */
    void markReady() {
        ready = true;
    }

    /** The caller holds the listener monitor. */
    boolean isReady() {
        return ready;
    }

    boolean isCurrent() {
        synchronized (SESSION_LOCK) {
            return current == this;
        }
    }

    /** Whether this is the connection Porter answers for, with no replacement attaching over it. */
    boolean isSoleConnection() {
        synchronized (SESSION_LOCK) {
            return current == this && latest == this;
        }
    }

    @NonNull
    PorterWire wire() {
        return wire;
    }

    int uid() {
        if (serverUid != -1) return serverUid;
        serverUid = wire.getUid();
        return serverUid;
    }

    /** The uid as far as it is already known, without asking the server for it. */
    int reportedUid() {
        return serverUid;
    }

    int protocolVersion() {
        return serverProtocolVersion;
    }

    long capabilities() {
        return serverCapabilities;
    }

    String seLinuxContext() {
        if (serverContext != null) return serverContext;
        serverContext = wire.getSELinuxContext();
        return serverContext;
    }

    private int permissionPushes() {
        synchronized (permissionLock) {
            return permissionStateGeneration;
        }
    }

    private void apply(@NonNull PorterWire.AttachReply reply, int pushes) {
        serverUid = reply.serverUid;
        serverProtocolVersion = reply.protocolVersion;
        serverContext = reply.seLinuxContext;
        serverCapabilities = reply.capabilities;
        synchronized (permissionLock) {
            // The server registers the client before it answers, so a state push can already have
            // overtaken this reply. It then describes the newer state.
            if (permissionStateGeneration == pushes) {
                permissionGranted = reply.permissionGranted;
                shouldShowRequestPermissionRationale = reply.shouldShowRequestPermissionRationale;
            }
        }
    }

    int checkSelfPermission() {
        int pushes;
        synchronized (permissionLock) {
            if (permissionGranted) return PackageManager.PERMISSION_GRANTED;
            pushes = permissionStateGeneration;
        }
        boolean granted = wire.checkSelfPermission();
        synchronized (permissionLock) {
            if (permissionStateGeneration == pushes) {
                permissionGranted = granted;
            } else {
                granted = permissionGranted;
            }
            return granted ? PackageManager.PERMISSION_GRANTED : PackageManager.PERMISSION_DENIED;
        }
    }

    boolean shouldShowRequestPermissionRationale() {
        int pushes;
        synchronized (permissionLock) {
            if (permissionGranted) return false;
            if (shouldShowRequestPermissionRationale) return true;
            pushes = permissionStateGeneration;
        }
        boolean rationale = wire.shouldShowRequestPermissionRationale();
        synchronized (permissionLock) {
            if (permissionStateGeneration == pushes) {
                shouldShowRequestPermissionRationale = rationale;
            } else {
                rationale = !permissionGranted && shouldShowRequestPermissionRationale;
            }
            return rationale;
        }
    }

    static void resetForTest() {
        synchronized (SESSION_LOCK) {
            current = null;
            latest = null;
        }
    }
}
