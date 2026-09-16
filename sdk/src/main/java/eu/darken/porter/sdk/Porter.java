package eu.darken.porter.sdk;

import static androidx.annotation.RestrictTo.Scope.LIBRARY_GROUP_PREFIX;
import static eu.darken.porter.protocol.PorterProtocol.CAPABILITIES_NONE;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Bundle;
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
import eu.darken.porter.server.IPorterService;

/** The app-facing entry point: the binder Porter delivered, and everything reachable through it. */
public final class Porter {

    private Porter() {
    }

    @RestrictTo(LIBRARY_GROUP_PREFIX)
    public static void onBinderReceived(@Nullable IBinder newBinder, String packageName) {
        PorterSession.onBinderReceived(newBinder, packageName);
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

    static void scheduleBinderDeadListeners() {
        List<ListenerHolder<OnBinderDeadListener>> listeners;
        synchronized (RECEIVED_LISTENERS) {
            listeners = new ArrayList<>(DEAD_LISTENERS);
        }
        for (ListenerHolder<OnBinderDeadListener> holder : listeners) {
            deliver(holder.handler, holder.listener::onBinderDead);
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
    private static IPorterService requireService() {
        return PorterSession.require().service();
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
     * Whether a Porter manager is installed, which is not whether its service is running: only
     * {@link Availability#CONNECTED} says a binder answered.
     *
     * <p>{@link Availability#INSTALLED_UNRECOGNIZED} means another package declares the permission.
     * Do not name or launch that package without your own verification.
     */
    @NonNull
    public static Availability getAvailability(@NonNull Context context) {
        if (pingBinder()) return Availability.CONNECTED;

        String owner;
        try {
            owner = context.getPackageManager()
                    .getPermissionInfo(PorterProtocol.PERMISSION, 0).packageName;
        } catch (PackageManager.NameNotFoundException e) {
            owner = null;
        }

        if (owner == null) return Availability.NOT_INSTALLED;
        if (!PorterProtocol.MANAGER_APPLICATION_ID.equals(owner)) {
            return Availability.INSTALLED_UNRECOGNIZED;
        }
        return Availability.INSTALLED_NOT_CONNECTED;
    }

    /**
     * Calls {@link IBinder#transact(int, Parcel, Parcel, int)} in the Porter server.
     *
     * @see PorterBinderWrapper
     */
    public static void transactRemote(@NonNull Parcel data, @Nullable Parcel reply, int flags) {
        PorterWire.transactRemote(requireService(), data, reply, flags);
    }

    /**
     * Starts a process in the Porter server; the arguments are passed to
     * {@link Runtime#exec(String[], String[], java.io.File)}. The process is killed when the caller
     * process dies. Read and write its streams from different threads.
     */
    @NonNull
    public static PorterRemoteProcess newProcess(
            @NonNull String[] cmd, @Nullable String[] env, @Nullable String dir) {
        return new PorterRemoteProcess(PorterWire.newProcess(requireService(), cmd, env, dir));
    }

    /**
     * @return uid of the Porter server
     * @throws IllegalStateException if called before a binder is received
     */
    public static int getUid() {
        return PorterSession.require().uid();
    }

    /** The protocol version the server reported when this connection attached; 0 if unreported. */
    public static int getServerProtocolVersion() {
        PorterSession session = PorterSession.current();
        return session == null ? 0 : session.protocolVersion();
    }

    /** Bitmask of the optional protocol features the server reported. */
    public static long getServerCapabilities() {
        PorterSession session = PorterSession.current();
        return session == null ? CAPABILITIES_NONE : session.capabilities();
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

        public Bundle forAdd() {
            return PorterWire.encodeUserService(this);
        }

        public Bundle forRemove(boolean remove) {
            return PorterWire.encodeUserServiceRemoval(this, remove);
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
        PorterServiceConnection connection = PorterServiceConnections.get(args);
        connection.addConnection(conn);
        PorterWire.addUserService(requireService(), connection, args.forAdd());
    }

    /**
     * As {@link #bindUserService}, but does not start the service if it is not running.
     *
     * @return the service version code if it is running, -1 if it is not
     */
    public static int peekUserService(@NonNull UserServiceArgs args, @NonNull ServiceConnection conn) {
        PorterServiceConnection connection = PorterServiceConnections.get(args);
        connection.addConnection(conn);
        return PorterWire.addUserService(
                requireService(), connection, PorterWire.withoutCreation(args.forAdd()));
    }

    /**
     * @param remove kill the remote user service; it is not killed otherwise
     * @see #bindUserService(UserServiceArgs, ServiceConnection)
     */
    public static void unbindUserService(
            @NonNull UserServiceArgs args, @Nullable ServiceConnection conn, boolean remove) {
        if (remove) {
            PorterWire.removeUserService(requireService(), null /* (unused) */, args.forRemove(true));
            return;
        }

        /*
         * The connection is a Binder the server still holds, so it would keep receiving "connected"
         * and "died" and keep calling the ServiceConnection callbacks after a later bind. Drop it
         * on the server first, then locally.
         */
        PorterServiceConnection connection = PorterServiceConnections.get(args);
        PorterWire.removeUserService(requireService(), connection, args.forRemove(false));
        connection.clearConnections();
        PorterServiceConnections.remove(connection);
    }

    /**
     * Whether the Porter server itself holds {@code permission}.
     *
     * @return {@link PackageManager#PERMISSION_GRANTED} or {@link PackageManager#PERMISSION_DENIED}
     */
    public static int checkRemotePermission(String permission) {
        PorterSession session = PorterSession.require();
        if (session.reportedUid() == 0) return PackageManager.PERMISSION_GRANTED;
        return PorterWire.checkPermission(session.service(), permission);
    }

    public static String getSystemProperty(String name, String defaultValue) {
        return PorterWire.getSystemProperty(requireService(), name, defaultValue);
    }

    public static void setSystemProperty(String name, String value) {
        PorterWire.setSystemProperty(requireService(), name, value);
    }

    /**
     * Unlike a runtime permission, the result arrives at a listener.
     *
     * @param requestCode matched with the result reported to
     *                    {@link OnRequestPermissionResultListener#onRequestPermissionResult(int, int)}
     * @see #addRequestPermissionResultListener(OnRequestPermissionResultListener)
     */
    public static void requestPermission(int requestCode) {
        PorterWire.requestPermission(requireService(), requestCode);
    }

    /**
     * @return {@link PackageManager#PERMISSION_GRANTED} or {@link PackageManager#PERMISSION_DENIED}
     */
    public static int checkSelfPermission() {
        return PorterSession.require().checkSelfPermission();
    }

    /** Whether to show a rationale before {@link #requestPermission(int)}. */
    public static boolean shouldShowRequestPermissionRationale() {
        return PorterSession.require().shouldShowRequestPermissionRationale();
    }

    // --------------------- non-app ----------------------

    @RestrictTo(LIBRARY_GROUP_PREFIX)
    public static void exit() {
        PorterWire.exit(requireService());
    }

    @RestrictTo(LIBRARY_GROUP_PREFIX)
    public static void attachUserService(@NonNull IBinder binder, @NonNull String token) {
        PorterWire.attachUserService(requireService(), binder, token);
    }

    @RestrictTo(LIBRARY_GROUP_PREFIX)
    public static void dispatchPermissionConfirmationResult(
            int requestUid, int requestPid, int requestCode, boolean allowed, boolean onetime) {
        PorterWire.dispatchPermissionConfirmationResult(
                requireService(), requestUid, requestPid, requestCode, allowed, onetime);
    }

    @RestrictTo(LIBRARY_GROUP_PREFIX)
    public static int getFlagsForUid(int uid, int mask) {
        return PorterWire.getFlagsForUid(requireService(), uid, mask);
    }

    @RestrictTo(LIBRARY_GROUP_PREFIX)
    public static void updateFlagsForUid(int uid, int mask, int value) {
        PorterWire.updateFlagsForUid(requireService(), uid, mask, value);
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
        synchronized (RECEIVED_LISTENERS) {
            RECEIVED_LISTENERS.clear();
            PENDING_STICKY.clear();
            DEAD_LISTENERS.clear();
            PERMISSION_LISTENERS.clear();
        }
    }
}
