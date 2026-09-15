package eu.darken.porter.sdk;

import static androidx.annotation.RestrictTo.Scope.LIBRARY_GROUP_PREFIX;
import static eu.darken.porter.protocol.PorterProtocol.ATTACH_PACKAGE_NAME;
import static eu.darken.porter.protocol.PorterProtocol.ATTACH_PROTOCOL_VERSION;
import static eu.darken.porter.protocol.PorterProtocol.CAPABILITIES_NONE;
import static eu.darken.porter.protocol.PorterProtocol.PERMISSION_RESULT_ALLOWED;
import static eu.darken.porter.protocol.PorterProtocol.REPLY_CAPABILITIES;
import static eu.darken.porter.protocol.PorterProtocol.REPLY_PERMISSION_GRANTED;
import static eu.darken.porter.protocol.PorterProtocol.REPLY_PROTOCOL_VERSION;
import static eu.darken.porter.protocol.PorterProtocol.REPLY_SERVER_SECONTEXT;
import static eu.darken.porter.protocol.PorterProtocol.REPLY_SERVER_UID;
import static eu.darken.porter.protocol.PorterProtocol.REPLY_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE;
import static eu.darken.porter.protocol.PorterProtocol.TRANSACTION_transactRemote;
import static eu.darken.porter.protocol.PorterProtocol.USER_SERVICE_COMPONENT;
import static eu.darken.porter.protocol.PorterProtocol.USER_SERVICE_DAEMON;
import static eu.darken.porter.protocol.PorterProtocol.USER_SERVICE_DEBUGGABLE;
import static eu.darken.porter.protocol.PorterProtocol.USER_SERVICE_NO_CREATE;
import static eu.darken.porter.protocol.PorterProtocol.USER_SERVICE_PROCESS_NAME_SUFFIX;
import static eu.darken.porter.protocol.PorterProtocol.USER_SERVICE_REMOVE;
import static eu.darken.porter.protocol.PorterProtocol.USER_SERVICE_TAG;
import static eu.darken.porter.protocol.PorterProtocol.USER_SERVICE_USE_32_BIT;
import static eu.darken.porter.protocol.PorterProtocol.USER_SERVICE_VERSION_CODE;

import android.content.ComponentName;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RestrictTo;
import androidx.annotation.VisibleForTesting;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import eu.darken.porter.protocol.PorterProtocol;
import eu.darken.porter.server.IPorterApplication;
import eu.darken.porter.server.IPorterService;

/** The app-facing entry point: the binder Porter delivered, and everything reachable through it. */
public final class Porter {

    private static final String TAG = "Porter";

    private Porter() {
    }

    private static IBinder binder;
    private static IPorterService service;

    private static int serverUid = -1;
    private static int serverProtocolVersion = 0;
    private static String serverContext = null;
    private static long serverCapabilities = CAPABILITIES_NONE;
    private static boolean permissionGranted = false;
    private static boolean shouldShowRequestPermissionRationale = false;
    private static boolean binderReady = false;

    private static final IPorterApplication APPLICATION = new IPorterApplication.Stub() {

        @Override
        public void dispatchRequestPermissionResult(int requestCode, Bundle data) {
            boolean allowed = data.getBoolean(PERMISSION_RESULT_ALLOWED, false);
            scheduleRequestPermissionResultListener(requestCode,
                    allowed ? PackageManager.PERMISSION_GRANTED : PackageManager.PERMISSION_DENIED);
        }
    };

    private static final IBinder.DeathRecipient DEATH_RECIPIENT = () -> {
        binderReady = false;
        onBinderReceived(null, null);
    };

    @RestrictTo(LIBRARY_GROUP_PREFIX)
    public static void onBinderReceived(@Nullable IBinder newBinder, String packageName) {
        if (binder == newBinder) return;

        // A grant belongs to the connection that reported it. The attach reply sets it again for a
        // new binder, so until then checkSelfPermission must not answer for the previous one.
        permissionGranted = false;
        shouldShowRequestPermissionRationale = false;

        if (newBinder == null) {
            binder = null;
            service = null;
            serverUid = -1;
            serverProtocolVersion = 0;
            serverContext = null;
            serverCapabilities = CAPABILITIES_NONE;

            scheduleBinderDeadListeners();
        } else {
            if (binder != null) {
                binder.unlinkToDeath(DEATH_RECIPIENT, 0);
            }
            binder = newBinder;
            service = IPorterService.Stub.asInterface(newBinder);

            try {
                binder.linkToDeath(DEATH_RECIPIENT, 0);
            } catch (Throwable e) {
                Log.i(TAG, "linkToDeath");
            }

            try {
                Bundle args = new Bundle();
                args.putString(ATTACH_PACKAGE_NAME, packageName);
                args.putInt(ATTACH_PROTOCOL_VERSION, PorterProtocol.VERSION);

                Bundle reply = service.attach(APPLICATION, args);

                // Reset first and read with the same values as defaults, so a reply that leaves a
                // key out reports a defined value rather than the previous connection's.
                serverUid = -1;
                serverProtocolVersion = 0;
                serverContext = null;
                serverCapabilities = CAPABILITIES_NONE;
                permissionGranted = false;
                shouldShowRequestPermissionRationale = false;

                if (reply != null) {
                    serverUid = reply.getInt(REPLY_SERVER_UID, -1);
                    serverProtocolVersion = reply.getInt(REPLY_PROTOCOL_VERSION, 0);
                    serverContext = reply.getString(REPLY_SERVER_SECONTEXT);
                    serverCapabilities = reply.getLong(REPLY_CAPABILITIES, CAPABILITIES_NONE);
                    permissionGranted = reply.getBoolean(REPLY_PERMISSION_GRANTED, false);
                    shouldShowRequestPermissionRationale =
                            reply.getBoolean(REPLY_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE, false);
                }

                Log.i(TAG, "attached");
                scheduleBinderReceivedListeners();
            } catch (RemoteException | RuntimeException e) {
                Log.w(TAG, Log.getStackTraceString(e));

                newBinder.unlinkToDeath(DEATH_RECIPIENT, 0);
                binder = null;
                service = null;
                serverUid = -1;
                serverProtocolVersion = 0;
                serverContext = null;
                serverCapabilities = CAPABILITIES_NONE;
                permissionGranted = false;
                shouldShowRequestPermissionRationale = false;
            }
        }
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
    private static final List<ListenerHolder<OnBinderDeadListener>> DEAD_LISTENERS = new ArrayList<>();
    private static final List<ListenerHolder<OnRequestPermissionResultListener>> PERMISSION_LISTENERS = new ArrayList<>();
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

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
        if (sticky && binderReady) {
            if (handler != null) {
                handler.post(listener::onBinderReceived);
            } else if (Looper.myLooper() == Looper.getMainLooper()) {
                listener.onBinderReceived();
            } else {
                MAIN_HANDLER.post(listener::onBinderReceived);
            }
        }
        synchronized (RECEIVED_LISTENERS) {
            RECEIVED_LISTENERS.add(new ListenerHolder<>(listener, handler));
        }
    }

    public static boolean removeBinderReceivedListener(@NonNull OnBinderReceivedListener listener) {
        synchronized (RECEIVED_LISTENERS) {
            return RECEIVED_LISTENERS.removeIf(holder -> holder.listener == listener);
        }
    }

    private static void scheduleBinderReceivedListeners() {
        synchronized (RECEIVED_LISTENERS) {
            for (ListenerHolder<OnBinderReceivedListener> holder : RECEIVED_LISTENERS) {
                if (holder.handler != null) {
                    holder.handler.post(holder.listener::onBinderReceived);
                } else {
                    if (Looper.myLooper() == Looper.getMainLooper()) {
                        holder.listener.onBinderReceived();
                    } else {
                        MAIN_HANDLER.post(holder.listener::onBinderReceived);
                    }
                }
            }
        }
        binderReady = true;
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

    private static void scheduleBinderDeadListeners() {
        synchronized (RECEIVED_LISTENERS) {
            for (ListenerHolder<OnBinderDeadListener> holder : DEAD_LISTENERS) {
                if (holder.handler != null) {
                    holder.handler.post(holder.listener::onBinderDead);
                } else {
                    if (Looper.myLooper() == Looper.getMainLooper()) {
                        holder.listener.onBinderDead();
                    } else {
                        MAIN_HANDLER.post(holder.listener::onBinderDead);
                    }
                }
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

    private static void scheduleRequestPermissionResultListener(int requestCode, int result) {
        synchronized (RECEIVED_LISTENERS) {
            for (ListenerHolder<OnRequestPermissionResultListener> holder : PERMISSION_LISTENERS) {
                if (holder.handler != null) {
                    holder.handler.post(() -> holder.listener.onRequestPermissionResult(requestCode, result));
                } else {
                    if (Looper.myLooper() == Looper.getMainLooper()) {
                        holder.listener.onRequestPermissionResult(requestCode, result);
                    } else {
                        MAIN_HANDLER.post(() -> holder.listener.onRequestPermissionResult(requestCode, result));
                    }
                }
            }
        }
    }

    @NonNull
    private static IPorterService requireService() {
        if (service == null) {
            throw new IllegalStateException("binder haven't been received");
        }
        return service;
    }

    /** Normal apps should not need this. */
    @Nullable
    public static IBinder getBinder() {
        return binder;
    }

    /**
     * Normal apps should use the listeners rather than calling this on every use.
     *
     * @see #addBinderReceivedListenerSticky(OnBinderReceivedListener)
     * @see #addBinderDeadListener(OnBinderDeadListener)
     */
    public static boolean pingBinder() {
        return binder != null && binder.pingBinder();
    }

    private static RuntimeException rethrowAsRuntimeException(RemoteException e) {
        return new RuntimeException(e);
    }

    /**
     * Calls {@link IBinder#transact(int, Parcel, Parcel, int)} in the Porter server.
     *
     * @see PorterBinderWrapper
     */
    public static void transactRemote(@NonNull Parcel data, @Nullable Parcel reply, int flags) {
        try {
            requireService().asBinder().transact(TRANSACTION_transactRemote, data, reply, flags);
        } catch (RemoteException e) {
            throw rethrowAsRuntimeException(e);
        }
    }

    /**
     * Starts a process in the Porter server; the arguments are passed to
     * {@link Runtime#exec(String[], String[], java.io.File)}. The process is killed when the caller
     * process dies. Read and write its streams from different threads.
     */
    @NonNull
    public static PorterRemoteProcess newProcess(
            @NonNull String[] cmd, @Nullable String[] env, @Nullable String dir) {
        try {
            return new PorterRemoteProcess(requireService().newProcess(cmd, env, dir));
        } catch (RemoteException e) {
            throw rethrowAsRuntimeException(e);
        }
    }

    /**
     * @return uid of the Porter server
     * @throws IllegalStateException if called before a binder is received
     */
    public static int getUid() {
        if (serverUid != -1) return serverUid;
        try {
            serverUid = requireService().getUid();
        } catch (RemoteException e) {
            throw rethrowAsRuntimeException(e);
        }
        return serverUid;
    }

    /** The protocol version the server reported when this connection attached; 0 if unreported. */
    public static int getServerProtocolVersion() {
        return serverProtocolVersion;
    }

    /** Bitmask of the optional protocol features the server reported. */
    public static long getServerCapabilities() {
        return serverCapabilities;
    }

    /**
     * SELinux context of the Porter server process. For adb this is {@code u:r:shell:s0}; for root
     * it depends on the su implementation.
     */
    public static String getSELinuxContext() {
        if (serverContext != null) return serverContext;
        try {
            serverContext = requireService().getSELinuxContext();
        } catch (RemoteException e) {
            throw rethrowAsRuntimeException(e);
        }
        return serverContext;
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
            Bundle args = new Bundle();
            args.putParcelable(USER_SERVICE_COMPONENT, componentName);
            args.putBoolean(USER_SERVICE_DEBUGGABLE, debuggable);
            args.putInt(USER_SERVICE_VERSION_CODE, versionCode);
            args.putBoolean(USER_SERVICE_DAEMON, daemon);
            args.putBoolean(USER_SERVICE_USE_32_BIT, use32BitAppProcess);
            args.putString(USER_SERVICE_PROCESS_NAME_SUFFIX,
                    Objects.requireNonNull(processName, "process name suffix must not be null"));
            if (tag != null) {
                args.putString(USER_SERVICE_TAG, tag);
            }
            return args;
        }

        public Bundle forRemove(boolean remove) {
            Bundle args = new Bundle();
            args.putParcelable(USER_SERVICE_COMPONENT, componentName);
            if (tag != null) {
                args.putString(USER_SERVICE_TAG, tag);
            }
            args.putBoolean(USER_SERVICE_REMOVE, remove);
            return args;
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
        try {
            requireService().addUserService(connection, args.forAdd());
        } catch (RemoteException e) {
            throw rethrowAsRuntimeException(e);
        }
    }

    /**
     * As {@link #bindUserService}, but does not start the service if it is not running.
     *
     * @return the service version code if it is running, -1 if it is not
     */
    public static int peekUserService(@NonNull UserServiceArgs args, @NonNull ServiceConnection conn) {
        PorterServiceConnection connection = PorterServiceConnections.get(args);
        connection.addConnection(conn);
        try {
            Bundle bundle = args.forAdd();
            bundle.putBoolean(USER_SERVICE_NO_CREATE, true);
            return requireService().addUserService(connection, bundle);
        } catch (RemoteException e) {
            throw rethrowAsRuntimeException(e);
        }
    }

    /**
     * @param remove kill the remote user service; it is not killed otherwise
     * @see #bindUserService(UserServiceArgs, ServiceConnection)
     */
    public static void unbindUserService(
            @NonNull UserServiceArgs args, @Nullable ServiceConnection conn, boolean remove) {
        if (remove) {
            try {
                requireService().removeUserService(null /* (unused) */, args.forRemove(true));
            } catch (RemoteException e) {
                throw rethrowAsRuntimeException(e);
            }
            return;
        }

        /*
         * The connection is a Binder the server still holds, so it would keep receiving "connected"
         * and "died" and keep calling the ServiceConnection callbacks after a later bind. Drop it
         * on the server first, then locally.
         */
        PorterServiceConnection connection = PorterServiceConnections.get(args);
        try {
            requireService().removeUserService(connection, args.forRemove(false));
        } catch (RemoteException e) {
            throw rethrowAsRuntimeException(e);
        }
        connection.clearConnections();
        PorterServiceConnections.remove(connection);
    }

    /**
     * Whether the Porter server itself holds {@code permission}.
     *
     * @return {@link PackageManager#PERMISSION_GRANTED} or {@link PackageManager#PERMISSION_DENIED}
     */
    public static int checkRemotePermission(String permission) {
        if (serverUid == 0) return PackageManager.PERMISSION_GRANTED;
        try {
            return requireService().checkPermission(permission);
        } catch (RemoteException e) {
            throw rethrowAsRuntimeException(e);
        }
    }

    public static String getSystemProperty(String name, String defaultValue) {
        try {
            return requireService().getSystemProperty(name, defaultValue);
        } catch (RemoteException e) {
            throw rethrowAsRuntimeException(e);
        }
    }

    public static void setSystemProperty(String name, String value) {
        try {
            requireService().setSystemProperty(name, value);
        } catch (RemoteException e) {
            throw rethrowAsRuntimeException(e);
        }
    }

    /**
     * Unlike a runtime permission, the result arrives at a listener.
     *
     * @param requestCode matched with the result reported to
     *                    {@link OnRequestPermissionResultListener#onRequestPermissionResult(int, int)}
     * @see #addRequestPermissionResultListener(OnRequestPermissionResultListener)
     */
    public static void requestPermission(int requestCode) {
        try {
            requireService().requestPermission(requestCode);
        } catch (RemoteException e) {
            throw rethrowAsRuntimeException(e);
        }
    }

    /**
     * @return {@link PackageManager#PERMISSION_GRANTED} or {@link PackageManager#PERMISSION_DENIED}
     */
    public static int checkSelfPermission() {
        if (permissionGranted) return PackageManager.PERMISSION_GRANTED;
        try {
            permissionGranted = requireService().checkSelfPermission();
        } catch (RemoteException e) {
            throw rethrowAsRuntimeException(e);
        }
        return permissionGranted ? PackageManager.PERMISSION_GRANTED : PackageManager.PERMISSION_DENIED;
    }

    /** Whether to show a rationale before {@link #requestPermission(int)}. */
    public static boolean shouldShowRequestPermissionRationale() {
        if (permissionGranted) return false;
        if (shouldShowRequestPermissionRationale) return true;
        try {
            shouldShowRequestPermissionRationale = requireService().shouldShowRequestPermissionRationale();
        } catch (RemoteException e) {
            throw rethrowAsRuntimeException(e);
        }
        return shouldShowRequestPermissionRationale;
    }

    /** Drops the connection and every listener, so one test cannot see another's state. */
    @VisibleForTesting
    public static void resetForTest() {
        binder = null;
        service = null;
        serverUid = -1;
        serverProtocolVersion = 0;
        serverContext = null;
        serverCapabilities = CAPABILITIES_NONE;
        permissionGranted = false;
        shouldShowRequestPermissionRationale = false;
        binderReady = false;
        synchronized (RECEIVED_LISTENERS) {
            RECEIVED_LISTENERS.clear();
            DEAD_LISTENERS.clear();
            PERMISSION_LISTENERS.clear();
        }
    }
}
