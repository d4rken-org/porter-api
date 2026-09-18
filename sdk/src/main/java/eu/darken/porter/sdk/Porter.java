package eu.darken.porter.sdk;

import static androidx.annotation.RestrictTo.Scope.LIBRARY_GROUP_PREFIX;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RestrictTo;
import androidx.annotation.VisibleForTesting;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import eu.darken.porter.protocol.PorterProtocol;

/** The app-facing entry point: the binder Porter delivered, and everything reachable through it. */
public final class Porter {

    private Porter() {
    }

    /** Announces a binder that speaks Porter's own wire. */
    @RestrictTo(LIBRARY_GROUP_PREFIX)
    public static void onBinderReceived(@Nullable IBinder newBinder, String packageName) {
        // No Context to select with, and nothing to select: this wire is Porter's by construction.
        PorterSession.onBinderReceived(newBinder, packageName, PorterBackend.PORTER);
    }

    /** A delivery from a server, which is taken only on the backend this process selected. */
    static void onBinderReceived(@NonNull Context context, @Nullable IBinder newBinder,
                                 String packageName, @NonNull PorterBackend backend) {
        PorterSession.onBinderReceived(context, newBinder, packageName, backend);
    }

    public interface OnBinderReceivedListener {
        void onBinderReceived();
    }

    public interface OnBinderDeadListener {
        void onBinderDead();
    }

    public interface OnRequestPermissionResultListener {

        /**
         * @param requestCode the code passed to {@link #requestPermission(int)}
         * @param grantResult {@link PackageManager#PERMISSION_GRANTED} or
         *                    {@link PackageManager#PERMISSION_DENIED}
         */
        void onRequestPermissionResult(int requestCode, int grantResult);
    }

    private static class ListenerHolder<T> {

        private final T listener;
        private final Handler handler;

        private ListenerHolder(@NonNull T listener, @Nullable Handler handler) {
            this.listener = listener;
            this.handler = handler;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ListenerHolder<?> that = (ListenerHolder<?>) o;
            return Objects.equals(listener, that.listener) && Objects.equals(handler, that.handler);
        }

        @Override
        public int hashCode() {
            return Objects.hash(listener, handler);
        }
    }

    private static final List<ListenerHolder<OnBinderReceivedListener>> RECEIVED_LISTENERS = new ArrayList<>();
    /**
     * The sticky listeners that read not-ready when they registered and have not been told since.
     * Guarded by the listener monitor, which is {@link #RECEIVED_LISTENERS}.
     */
    private static final List<ListenerHolder<OnBinderReceivedListener>> PENDING_STICKY = new ArrayList<>();
    private static final List<ListenerHolder<OnBinderDeadListener>> DEAD_LISTENERS = new ArrayList<>();
    private static final List<ListenerHolder<OnRequestPermissionResultListener>> PERMISSION_LISTENERS = new ArrayList<>();
    /**
     * What the SDK itself does once every dead listener has been told, which is where the SDK's own
     * reaction to a death belongs: a listener would be told in registration order, ahead of an app
     * that registered its listeners later. Guarded by the listener monitor.
     */
    private static final List<Runnable> POST_DEAD_HOOKS = new ArrayList<>();
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    /** Runs {@code delivery} on {@code handler}, or on the main thread when there is none. */
    private static void deliver(@Nullable Handler handler, @NonNull Runnable delivery) {
        if (handler != null) {
            handler.post(delivery);
        } else if (Looper.myLooper() == Looper.getMainLooper()) {
            delivery.run();
        } else {
            MAIN_HANDLER.post(delivery);
        }
    }

    /**
     * The listener is called every time a binder arrives, which happens again whenever the user
     * restarts Porter while the app is running.
     */
    public static void addBinderReceivedListener(@NonNull OnBinderReceivedListener listener) {
        addBinderReceivedListener(listener, null);
    }

    /**
     * @param handler where to call the listener; the main thread when null
     * @see #addBinderReceivedListener(OnBinderReceivedListener)
     */
    public static void addBinderReceivedListener(
            @NonNull OnBinderReceivedListener listener, @Nullable Handler handler) {
        addBinderReceivedListener(Objects.requireNonNull(listener), false, handler);
    }

    /** As {@link #addBinderReceivedListener}, but calls the listener at once if a binder is held. */
    public static void addBinderReceivedListenerSticky(@NonNull OnBinderReceivedListener listener) {
        addBinderReceivedListenerSticky(Objects.requireNonNull(listener), null);
    }

    /** @see #addBinderReceivedListenerSticky(OnBinderReceivedListener) */
    public static void addBinderReceivedListenerSticky(
            @NonNull OnBinderReceivedListener listener, @Nullable Handler handler) {
        addBinderReceivedListener(Objects.requireNonNull(listener), true, handler);
    }

    private static void addBinderReceivedListener(
            @NonNull OnBinderReceivedListener listener, boolean sticky, @Nullable Handler handler) {
        ListenerHolder<OnBinderReceivedListener> holder = new ListenerHolder<>(listener, handler);
        boolean ready;
        synchronized (RECEIVED_LISTENERS) {
            RECEIVED_LISTENERS.add(holder);
            ready = PorterSession.latestIsReady();
            // Not ready can mean a replacement is attaching over a connection that is ready and
            // answering. Nothing tells this listener about that one unless the replacement fails.
            if (sticky && !ready) PENDING_STICKY.add(holder);
        }
        if (sticky && ready) {
            deliver(handler, listener::onBinderReceived);
        }
    }

    public static boolean removeBinderReceivedListener(@NonNull OnBinderReceivedListener listener) {
        synchronized (RECEIVED_LISTENERS) {
            PENDING_STICKY.removeIf(holder -> holder.listener == listener);
            return RECEIVED_LISTENERS.removeIf(holder -> holder.listener == listener);
        }
    }

    /**
     * A listener is free to add or remove one from inside its own callback, so the dispatch walks a
     * copy with the monitor released rather than the list a callback can still reach.
     *
     * <p>Marking the connection ready and taking that copy in one critical section is what makes a
     * sticky listener registering during the dispatch called exactly once: it either reads
     * not-ready and is in the copy, or reads ready and calls itself.
     */
    static void scheduleBinderReceivedListeners(@NonNull PorterSession session) {
        List<ListenerHolder<OnBinderReceivedListener>> listeners;
        synchronized (RECEIVED_LISTENERS) {
            // The connection can die between publishing itself and getting here. There is then no
            // binder to announce: a listener asking for one inside the callback would be refused.
            if (!session.isCurrent()) return;
            session.markReady();
            listeners = new ArrayList<>(RECEIVED_LISTENERS);
            // Everything still registered is in this copy, so nothing is left waiting to be told.
            PENDING_STICKY.clear();
        }
        for (ListenerHolder<OnBinderReceivedListener> holder : listeners) {
            deliver(holder.handler, holder.listener::onBinderReceived);
        }
    }

    /**
     * Tells the sticky listeners that read not-ready while {@code retained} was being replaced,
     * now that the replacement is gone and {@code retained} is the connection again. Its readiness
     * is left alone: it announced itself once and is not announcing itself again to anyone else.
     */
    static void scheduleStickyCatchUp(@NonNull PorterSession retained) {
        List<ListenerHolder<OnBinderReceivedListener>> listeners;
        synchronized (RECEIVED_LISTENERS) {
            // Another replacement can still be attaching over the retained connection: the pending
            // listeners are then waiting for that one, and its own dispatch or rollback tells them.
            if (!retained.isReady() || !retained.isSoleConnection()) return;
            listeners = new ArrayList<>(PENDING_STICKY);
            PENDING_STICKY.clear();
        }
        for (ListenerHolder<OnBinderReceivedListener> holder : listeners) {
            deliver(holder.handler, holder.listener::onBinderReceived);
        }
    }

    public static void addBinderDeadListener(@NonNull OnBinderDeadListener listener) {
        addBinderDeadListener(listener, null);
    }

    /** @param handler where to call the listener; the main thread when null */
    public static void addBinderDeadListener(@NonNull OnBinderDeadListener listener, @Nullable Handler handler) {
        synchronized (RECEIVED_LISTENERS) {
            DEAD_LISTENERS.add(new ListenerHolder<>(listener, handler));
        }
    }

    public static boolean removeBinderDeadListener(@NonNull OnBinderDeadListener listener) {
        synchronized (RECEIVED_LISTENERS) {
            return DEAD_LISTENERS.removeIf(holder -> holder.listener == listener);
        }
    }

    /** Runs on the main thread after every dead listener of that death has been told. */
    static void addPostBinderDeadHook(@NonNull Runnable hook) {
        synchronized (RECEIVED_LISTENERS) {
            POST_DEAD_HOOKS.add(Objects.requireNonNull(hook));
        }
    }

    static void scheduleBinderDeadListeners() {
        List<ListenerHolder<OnBinderDeadListener>> listeners;
        List<Runnable> hooks;
        synchronized (RECEIVED_LISTENERS) {
            listeners = new ArrayList<>(DEAD_LISTENERS);
            hooks = new ArrayList<>(POST_DEAD_HOOKS);
        }
        try {
            for (ListenerHolder<OnBinderDeadListener> holder : listeners) {
                deliver(holder.handler, holder.listener::onBinderDead);
            }
        } finally {
            // The hooks go on the main queue behind every listener delivery this dispatch has
            // scheduled or run, rather than on the dispatching stack. A listener on a handler that
            // is not the main looper asked for another thread and is not ordered against them.
            for (Runnable hook : hooks) {
                MAIN_HANDLER.post(hook);
            }
        }
    }

    public static void addRequestPermissionResultListener(@NonNull OnRequestPermissionResultListener listener) {
        addRequestPermissionResultListener(listener, null);
    }

    /** @param handler where to call the listener; the main thread when null */
    public static void addRequestPermissionResultListener(
            @NonNull OnRequestPermissionResultListener listener, @Nullable Handler handler) {
        synchronized (RECEIVED_LISTENERS) {
            PERMISSION_LISTENERS.add(new ListenerHolder<>(listener, handler));
        }
    }

    public static boolean removeRequestPermissionResultListener(
            @NonNull OnRequestPermissionResultListener listener) {
        synchronized (RECEIVED_LISTENERS) {
            return PERMISSION_LISTENERS.removeIf(holder -> holder.listener == listener);
        }
    }

    static void scheduleRequestPermissionResultListener(
            @NonNull PorterSession session, int requestCode, int result) {
        List<ListenerHolder<OnRequestPermissionResultListener>> listeners;
        synchronized (RECEIVED_LISTENERS) {
            listeners = new ArrayList<>(PERMISSION_LISTENERS);
        }
        for (ListenerHolder<OnRequestPermissionResultListener> holder : listeners) {
            // A result belongs to the connection that asked for it, and only while that
            // connection is the one Porter answers for. The check runs where the callback runs, so
            // one already queued to a Handler is dropped as well.
            deliver(holder.handler, () -> {
                if (!session.isCurrent()) return;
                holder.listener.onRequestPermissionResult(requestCode, result);
            });
        }
    }

    @NonNull
    static PorterWire requireWire() {
        return PorterSession.require().wire();
    }

    /** Normal apps should not need this. */
    @Nullable
    public static IBinder getBinder() {
        return PorterSession.currentBinder();
    }

    /**
     * Normal apps should use the listeners rather than calling this on every use.
     *
     * @see #addBinderReceivedListenerSticky(OnBinderReceivedListener)
     * @see #addBinderDeadListener(OnBinderDeadListener)
     */
    public static boolean pingBinder() {
        return PorterSession.pingCurrent();
    }

    /** How far away Porter is, from a binder in hand to nothing installed at all. */
    public enum Availability {
        /** No installed package declares the Porter permission. */
        NOT_INSTALLED,
        /** The permission belongs to a package this SDK does not recognize as the manager. */
        INSTALLED_UNRECOGNIZED,
        /** The manager is installed; this process has no binder from it. */
        INSTALLED_NOT_CONNECTED,
        /** A binder is held and answers. */
        CONNECTED
    }

    /**
     * Whether the manager of the backend this process selected is installed, which is not whether
     * its service is running: only {@link Availability#CONNECTED} says a binder answered.
     *
     * <p>The backend is Porter whenever a package declares Porter's permission, and Shizuku when one
     * declares Shizuku's and the optional {@code shizuku-compat} artifact is on the classpath. An app
     * without that artifact can receive no Shizuku binder at all, so a Shizuku-only device reads
     * {@link Availability#NOT_INSTALLED} rather than promising a connection it cannot make.
     *
     * <p>{@link Availability#INSTALLED_UNRECOGNIZED} means a package owns the selected backend's
     * permission and is not the manager this SDK knows. Do not name or launch it without your own
     * verification.
     *
     * <p>From API 30 this answers only about packages the app can see, and the SDK's manifest names
     * {@code eu.darken.porter} and {@code moe.shizuku.privileged.api}. A manager published under some
     * other package name may therefore read {@link Availability#NOT_INSTALLED} here.
     */
    @NonNull
    public static Availability getAvailability(@NonNull Context context) {
        if (pingBinder()) return Availability.CONNECTED;

        switch (PorterSession.selectBackend(context)) {
            case PORTER:
                return availabilityOf(context, PorterProtocol.PERMISSION,
                        PorterProtocol.MANAGER_APPLICATION_ID);
            case SHIZUKU:
                return availabilityOf(context, ShizukuProtocol.PERMISSION,
                        ShizukuProtocol.MANAGER_APPLICATION_ID);
            default:
                return Availability.NOT_INSTALLED;
        }
    }

    @NonNull
    private static Availability availabilityOf(@NonNull Context context, @NonNull String permission,
                                               @NonNull String manager) {
        String owner = PorterSession.permissionOwner(context, permission);
        if (owner == null) return Availability.NOT_INSTALLED;
        return manager.equals(owner)
                ? Availability.INSTALLED_NOT_CONNECTED
                : Availability.INSTALLED_UNRECOGNIZED;
    }

    /**
     * Calls {@link IBinder#transact(int, Parcel, Parcel, int)} in the Porter server.
     *
     * @see PorterBinderWrapper
     */
    public static void transactRemote(@NonNull Parcel data, @Nullable Parcel reply, int flags) {
        requireWire().transactRemote(data, reply, flags);
    }

    /**
     * @return uid of the Porter server
     * @throws IllegalStateException if called before a binder is received
     */
    public static int getUid() {
        return PorterSession.require().uid();
    }

    /**
     * The protocol version the server reported when this connection attached; 0 if unreported.
     *
     * @deprecated the number is on the connected backend's own scale. {@link #getServerInfo()}
     * reports it together with the backend it belongs to.
     */
    @Deprecated
    public static int getServerProtocolVersion() {
        PorterSession session = PorterSession.current();
        return session == null ? 0 : session.protocolVersion();
    }

    /** What this connection's server reported when it attached, or null if nothing is connected. */
    @Nullable
    public static PorterServerInfo getServerInfo() {
        PorterSession session = PorterSession.current();
        return session == null ? null : session.serverInfo();
    }

    /**
     * SELinux context of the Porter server process. For adb this is {@code u:r:shell:s0}; for root
     * it depends on the su implementation.
     */
    public static String getSELinuxContext() {
        return PorterSession.require().seLinuxContext();
    }

    public static class UserServiceArgs {

        final ComponentName componentName;
        int versionCode = 1;
        String processName;
        String tag;
        boolean debuggable = false;
        boolean daemon = true;
        boolean use32BitAppProcess = false;

        public UserServiceArgs(@NonNull ComponentName componentName) {
            this.componentName = componentName;
        }

        /**
         * A daemon service runs until {@link Porter#unbindUserService} is called; a non-daemon one
         * is stopped when the process that bound it dies. Daemon by default.
         */
        public UserServiceArgs daemon(boolean daemon) {
            this.daemon = daemon;
            return this;
        }

        /**
         * Distinguishes services of one app. Set a stable tag if the service class is obfuscated.
         */
        public UserServiceArgs tag(@NonNull String tag) {
            this.tag = tag;
            return this;
        }

        /** Use a new version code when the service code changes, so the server recreates it. */
        public UserServiceArgs version(int versionCode) {
            this.versionCode = versionCode;
            return this;
        }

        /** A debuggable service process is listed when "Show all processes" is enabled. */
        public UserServiceArgs debuggable(boolean debuggable) {
            this.debuggable = debuggable;
            return this;
        }

        /** The final process name is {@code com.example:suffix}. */
        public UserServiceArgs processNameSuffix(String processNameSuffix) {
            this.processName = processNameSuffix;
            return this;
        }

        private UserServiceArgs use32BitAppProcess(boolean use32BitAppProcess) {
            this.use32BitAppProcess = use32BitAppProcess;
            return this;
        }
    }

    /**
     * A user service is a bound service that runs in its own process, as the identity the Porter
     * server runs as.
     *
     * <p>Unbinding does not kill it: implement a "destroy" method under transaction code
     * {@code 16777115} ({@code 16777114} in aidl) that cleans up and calls {@link System#exit(int)}.
     *
     * <p>The service process is not a valid Android application process. A {@code Context} obtained
     * there cannot register receivers or reach a content resolver.
     *
     * @see UserServiceArgs
     */
    public static void bindUserService(@NonNull UserServiceArgs args, @NonNull ServiceConnection conn) {
        PorterServiceConnections.Registration registration = PorterServiceConnections.register(args, conn);
        try {
            requireWire().addUserService(registration.connection, args, false);
        } catch (RuntimeException e) {
            // Removes the registration this call made, and no lifecycle state: a death either
            // happened, in which case the binding is retired and evicted, or it did not.
            if (registration.inserted) registration.connection.removeConnection(conn);
            throw e;
        }
    }

    /**
     * As {@link #bindUserService}, but does not start the service if it is not running.
     *
     * @return what the server answers: -1 when the service is not running, and at this SDK's
     * protocol floor the running service's version code on either backend
     */
    public static int peekUserService(@NonNull UserServiceArgs args, @NonNull ServiceConnection conn) {
        PorterServiceConnections.Registration registration = PorterServiceConnections.register(args, conn);
        try {
            return requireWire().addUserService(registration.connection, args, true);
        } catch (RuntimeException e) {
            if (registration.inserted) registration.connection.removeConnection(conn);
            throw e;
        }
    }

    /**
     * @param remove kill the remote user service; it is not killed otherwise
     * @see #bindUserService(UserServiceArgs, ServiceConnection)
     */
    public static void unbindUserService(
            @NonNull UserServiceArgs args, @Nullable ServiceConnection conn, boolean remove) {
        if (remove) {
            requireWire().removeUserService(null /* (unused) */, args, true);
            return;
        }

        /*
         * The connection is a Binder the server still holds, so it would keep receiving "connected"
         * and "died" and keep calling the ServiceConnection callbacks after a later bind. Drop it
         * on the server first, then locally.
         */
        PorterServiceConnection connection = PorterServiceConnections.get(args);
        try {
            requireWire().removeUserService(connection, args, false);
        } finally {
            connection.clearConnections();
            PorterServiceConnections.remove(connection);
            PorterServiceConnections.cancelPending(args);
        }
    }

    /**
     * Whether the Porter server itself holds {@code permission}.
     *
     * @return {@link PackageManager#PERMISSION_GRANTED} or {@link PackageManager#PERMISSION_DENIED}
     */
    public static int checkRemotePermission(String permission) {
        PorterSession session = PorterSession.require();
        if (session.reportedUid() == 0) return PackageManager.PERMISSION_GRANTED;
        return session.wire().checkPermission(permission);
    }

    public static String getSystemProperty(String name, String defaultValue) {
        return requireWire().getSystemProperty(name, defaultValue);
    }

    public static void setSystemProperty(String name, String value) {
        requireWire().setSystemProperty(name, value);
    }

    /**
     * Unlike a runtime permission, the result arrives at a listener.
     *
     * @param requestCode matched with the result reported to
     *                    {@link OnRequestPermissionResultListener#onRequestPermissionResult(int, int)}
     * @see #addRequestPermissionResultListener(OnRequestPermissionResultListener)
     */
    public static void requestPermission(int requestCode) {
        requireWire().requestPermission(requestCode);
    }

    /**
     * On the Shizuku backend the permission state is synthesized by the SDK from a server's re-sent
     * attach state, because that wire carries no callback of its own for it. A server that never
     * re-sends leaves a cached grant in place until its binder dies.
     *
     * @return {@link PackageManager#PERMISSION_GRANTED} or {@link PackageManager#PERMISSION_DENIED}
     */
    public static int checkSelfPermission() {
        return PorterSession.require().checkSelfPermission();
    }

    /**
     * Whether to show a rationale before {@link #requestPermission(int)}.
     *
     * <p>On the Shizuku backend this is synthesized the same way {@link #checkSelfPermission()} is.
     */
    public static boolean shouldShowRequestPermissionRationale() {
        return PorterSession.require().shouldShowRequestPermissionRationale();
    }

    // --------------------- non-app ----------------------

    @RestrictTo(LIBRARY_GROUP_PREFIX)
    public static void exit() {
        requireWire().exit();
    }

    @RestrictTo(LIBRARY_GROUP_PREFIX)
    public static void attachUserService(@NonNull IBinder binder, @NonNull String token) {
        requireWire().attachUserService(binder, token);
    }

    @RestrictTo(LIBRARY_GROUP_PREFIX)
    public static void dispatchPermissionConfirmationResult(
            int requestUid, int requestPid, int requestCode, boolean allowed, boolean onetime) {
        requireWire().dispatchPermissionConfirmationResult(
                requestUid, requestPid, requestCode, allowed, onetime);
    }

    @RestrictTo(LIBRARY_GROUP_PREFIX)
    public static int getFlagsForUid(int uid, int mask) {
        return requireWire().getFlagsForUid(uid, mask);
    }

    @RestrictTo(LIBRARY_GROUP_PREFIX)
    public static void updateFlagsForUid(int uid, int mask, int value) {
        requireWire().updateFlagsForUid(uid, mask, value);
    }

    /**
     * Whether the connection Porter answers for has already told the received listeners about
     * itself. Read-only on purpose: {@link #resetForTest()} is what a test uses to get back to a
     * process with no connection and no listeners.
     */
    @VisibleForTesting
    public static boolean isBinderReadyForTest() {
        synchronized (RECEIVED_LISTENERS) {
            PorterSession session = PorterSession.current();
            return session != null && session.isReady();
        }
    }

    /** Drops the connection and every listener, so one test cannot see another's state. */
    @VisibleForTesting
    public static void resetForTest() {
        PorterSession.resetForTest();
        ShizukuCompat.setPresentForTest(null);
        PorterServiceConnections.clearForTest();
        synchronized (RECEIVED_LISTENERS) {
            RECEIVED_LISTENERS.clear();
            PENDING_STICKY.clear();
            DEAD_LISTENERS.clear();
            PERMISSION_LISTENERS.clear();
            POST_DEAD_HOOKS.clear();
        }
    }
}
