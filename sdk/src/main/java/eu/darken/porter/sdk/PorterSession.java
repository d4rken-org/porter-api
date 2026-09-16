package eu.darken.porter.sdk;

import static eu.darken.porter.protocol.PorterProtocol.CAPABILITIES_NONE;
import static eu.darken.porter.protocol.PorterProtocol.PERMISSION_RESULT_ALLOWED;
import static eu.darken.porter.protocol.PorterProtocol.REPLY_PERMISSION_GRANTED;
import static eu.darken.porter.protocol.PorterProtocol.REPLY_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE;

import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import eu.darken.porter.server.IPorterApplication;
import eu.darken.porter.server.IPorterService;

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
    private final IPorterService service;

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

    private final IPorterApplication application = new IPorterApplication.Stub() {

        @Override
        public void dispatchRequestPermissionResult(int requestCode, Bundle data) {
            boolean allowed = data.getBoolean(PERMISSION_RESULT_ALLOWED, false);
            Porter.scheduleRequestPermissionResultListener(PorterSession.this, requestCode,
                    allowed ? PackageManager.PERMISSION_GRANTED : PackageManager.PERMISSION_DENIED);
        }

        @Override
        public void dispatchPermissionStateChanged(Bundle state) {
            boolean granted = state.getBoolean(REPLY_PERMISSION_GRANTED, false);
            boolean rationale = state.getBoolean(REPLY_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE, false);
            synchronized (SESSION_LOCK) {
                // Both callbacks are oneway, so a server that has been replaced can still have one
                // in flight; it describes a connection nobody asks about any more.
                if (latest != PorterSession.this) return;
                synchronized (permissionLock) {
                    permissionGranted = granted;
                    shouldShowRequestPermissionRationale = rationale;
                    permissionStateGeneration++;
                }
            }
        }
    };

    private PorterSession(int generation, @NonNull IBinder binder) {
        this.generation = generation;
        this.binder = binder;
        this.service = PorterWire.asService(binder);
    }

    static void onBinderReceived(@Nullable IBinder newBinder, String packageName) {
        if (newBinder == null) {
            dropCurrent();
            return;
        }

        PorterSession session;
        synchronized (SESSION_LOCK) {
            // The same binder delivered twice is the same connection. Attaching again would ask the
            // server for a second grant for it and link a second death recipient.
            if (latest != null && latest.binder == newBinder) return;

            PorterSession replaced = latest;
            session = new PorterSession(++connections, newBinder);
            latest = session;
            session.link();
            if (replaced != null) replaced.unlink();
        }

        try {
            int pushes = session.permissionPushes();
            PorterWire.AttachReply reply =
                    PorterWire.attach(session.service, session.application, packageName);
            if (reply != null) session.apply(reply, pushes);

            boolean superseded;
            synchronized (SESSION_LOCK) {
                superseded = latest != session;
                if (!superseded) current = session;
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
        synchronized (SESSION_LOCK) {
            if (current == this) current = null;
            // Fall back to the published connection rather than to nothing: a newcomer that failed
            // must not silence the one that is still serving calls.
            if (latest == this) latest = current;
        }
        unlink();
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
        synchronized (SESSION_LOCK) {
            // The notification names no binder, so only the connection it was linked for may act on
            // it. One for a binder that has already been replaced tears nothing down.
            wasCurrent = current == this;
            if (wasCurrent) {
                current = null;
                if (latest == this) latest = null;
            }
        }
        if (wasCurrent) Porter.scheduleBinderDeadListeners();
    }

    private void link() {
        try {
            binder.linkToDeath(deathRecipient, 0);
        } catch (Throwable e) {
            Log.i(TAG, "linkToDeath");
        }
    }

    private void unlink() {
        binder.unlinkToDeath(deathRecipient, 0);
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

    boolean isLatest() {
        synchronized (SESSION_LOCK) {
            return latest == this;
        }
    }

    @NonNull
    IPorterService service() {
        return service;
    }

    int uid() {
        if (serverUid != -1) return serverUid;
        serverUid = PorterWire.getUid(service);
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
        serverContext = PorterWire.getSELinuxContext(service);
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
        boolean granted = PorterWire.checkSelfPermission(service);
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
        boolean rationale = PorterWire.shouldShowRequestPermissionRationale(service);
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
