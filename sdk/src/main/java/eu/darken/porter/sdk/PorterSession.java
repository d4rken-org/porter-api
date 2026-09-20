package eu.darken.porter.sdk;

import static eu.darken.porter.protocol.PorterProtocol.CAPABILITIES_NONE;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import eu.darken.porter.protocol.PorterProtocol;

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

    /**
     * The wire this process takes a server delivery on. Kept out of {@link PorterBackend}, which
     * {@link PorterServerInfo#backend} publishes and which describes a connection that exists.
     */
    enum Selection {
        PORTER,
        SHIZUKU,
        /** Neither manager is reachable, so no delivery is taken at all. */
        NONE
    }

    /** The answer a live connection was selected on, or the last one resolved. Guarded by SESSION_LOCK. */
    private static Selection selection;
    /** What a test pins the answer to. Guarded by SESSION_LOCK. */
    private static Selection selectionForTest;

    private final int generation;
    private final IBinder binder;
    private final PorterBackend backend;
    private final PorterWire wire;

    private int serverUid = -1;
    private int serverProtocolVersion = 0;
    private Integer serverPatchVersion = null;
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
        this.wire = backend == PorterBackend.SHIZUKU
                ? new ShizukuProtocolWire(binder, new SessionCallbacks())
                : new PorterProtocolWire(binder, new SessionCallbacks());
    }

    /**
     * A delivery from a server, which this process takes only on the backend it selected. Arrival
     * order therefore never decides which server an app talks to, and neither does a death.
     */
    static void onBinderReceived(@NonNull Context context, @Nullable IBinder newBinder,
                                 String packageName, @NonNull PorterBackend backend) {
        // Resolving asks the package manager, so it happens before the lock; the answer decides
        // nothing until it is checked inside, against the connection that is published then.
        Selection selected = newBinder == null ? null : selectBackend(context);
        onBinderReceived(newBinder, packageName, backend, selected);
    }

    static void onBinderReceived(@Nullable IBinder newBinder, String packageName,
                                 @NonNull PorterBackend backend) {
        onBinderReceived(newBinder, packageName, backend, null);
    }

    /**
     * @param selected the backend this process resolved for this delivery, or null where the caller
     *                 delivers a binder it already knows this process is entitled to take.
     */
    private static void onBinderReceived(@Nullable IBinder newBinder, String packageName,
                                         @NonNull PorterBackend backend,
                                         @Nullable Selection selected) {
        if (newBinder == null) {
            dropCurrent();
            return;
        }

        PorterSession session;
        synchronized (SESSION_LOCK) {
            // The same binder delivered twice is the same connection. Attaching again would ask the
            // server for a second grant for it, link a second death recipient, and supersede a
            // replacement that is attaching, so the published connection counts as well as latest.
            if (current != null && current.binder == newBinder) return;
            if (latest != null && latest.binder == newBinder) return;

            // A live connection is the answer, whoever delivers and however the delivery got here:
            // no path replaces the server an app is talking to with one on the other backend.
            if (current != null && current.backend != backend) {
                Log.i(TAG, "ignoring a " + backend + " binder, connected on " + current.backend);
                return;
            }
            if (selected != null && selected != selectionOf(backend)) {
                Log.i(TAG, "ignoring a " + backend + " binder, this process selected " + selected);
                return;
            }

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
                    // A published connection names this process's selection rather than only being
                    // constrained by it, so whatever was resolved before it published cannot leave
                    // the process answering for a backend it is not connected on.
                    selection = selectionOf(session.backend);
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
        // Whatever this session had published, it is its own binder that died, and a wire still
        // waiting on that binder has nothing left to wait for. Outside the lock, as the wire counts
        // its own attach latch down outside its own.
        wire.onPeerDied();
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

    /** The published binder, but only if that connection speaks {@code backend}. */
    @Nullable
    static IBinder binderFor(@NonNull PorterBackend backend) {
        PorterSession session = current();
        if (session == null || session.backend != backend) return null;
        return session.binder.pingBinder() ? session.binder : null;
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

    @NonNull
    PorterServerInfo serverInfo() {
        return new PorterServerInfo(backend, serverProtocolVersion, serverPatchVersion);
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
        serverPatchVersion = reply.patchVersion;
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

    /**
     * Which backend this process accepts a delivery on: Porter whenever any visible package claims
     * Porter's permission, otherwise Shizuku when one claims Shizuku's and the optional
     * {@code shizuku-compat} artifact is on the classpath, otherwise nothing at all.
     *
     * <p>Held constant while a connection is live, and resolved again whenever there is none: a
     * running connection never changes backend, and a process that has none sees an install that
     * happened after it last asked.
     */
    @NonNull
    static Selection selectBackend(@NonNull Context context) {
        Selection pinned;
        synchronized (SESSION_LOCK) {
            if (selectionForTest != null) return selectionForTest;
            pinned = current != null ? selection : null;
        }
        if (pinned != null) return pinned;

        // Resolved outside the lock, because it asks the package manager, which is a binder call.
        Selection resolved = resolve(context);
        synchronized (SESSION_LOCK) {
            // A connection that published while this was resolving keeps what it was selected on,
            // and names the answer itself where nothing was recorded: the resolved value lost the
            // race, and writing it would leave this process answering for the other backend.
            if (current != null) {
                if (selection == null) selection = selectionOf(current.backend);
                return selection;
            }
            selection = resolved;
            return resolved;
        }
    }

    @NonNull
    private static Selection resolve(@NonNull Context context) {
        if (permissionOwner(context, PorterProtocol.PERMISSION) != null) return Selection.PORTER;
        // Without the compat artifact no Shizuku binder can be unwrapped, so a Shizuku server this
        // app cannot receive from is not a backend to wait for.
        if (ShizukuCompat.isPresent()
                && permissionOwner(context, ShizukuProtocol.PERMISSION) != null) {
            return Selection.SHIZUKU;
        }
        return Selection.NONE;
    }

    /**
     * Takes {@code backend} as this process's selection, for a binder another process of this app
     * already selected and is using. Whoever asks next reads that instead of deciding again, unless
     * this process has a connection of its own, which answers for itself.
     */
    static void adoptBackend(@NonNull PorterBackend backend) {
        synchronized (SESSION_LOCK) {
            // A connection of this process is already the answer; another process's is not a reason
            // to move the selection off the backend this one is connected on.
            if (current != null) return;
            selection = selectionOf(backend);
        }
    }

    @NonNull
    private static Selection selectionOf(@NonNull PorterBackend backend) {
        return backend == PorterBackend.SHIZUKU ? Selection.SHIZUKU : Selection.PORTER;
    }

    /** The package declaring {@code permission}, or null where no package this app can see does. */
    @Nullable
    static String permissionOwner(@NonNull Context context, @NonNull String permission) {
        try {
            return context.getPackageManager().getPermissionInfo(permission, 0).packageName;
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    /** Pins the selection a test runs against; {@link Porter#resetForTest()} clears it. */
    @VisibleForTesting
    static void selectBackendForTest(@Nullable Selection pinned) {
        synchronized (SESSION_LOCK) {
            selectionForTest = pinned;
        }
    }

    static void resetForTest() {
        synchronized (SESSION_LOCK) {
            current = null;
            latest = null;
            selection = null;
            selectionForTest = null;
        }
    }
}
