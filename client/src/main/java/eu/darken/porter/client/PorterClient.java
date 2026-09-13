package eu.darken.porter.client;

import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Objects;

import rikka.shizuku.ShizukuProvider;
import rikka.sui.Sui;

/** Select a backend before connecting; changing an established session requires an app restart. */
public final class PorterClient {
    public static final String PERMISSION = "eu.darken.porter.permission.API_V23";
    public static final String PORTER_ONLY = "eu.darken.porter.client.PORTER_ONLY";
    /** Package of the standalone Porter manager. */
    public static final String PORTER_APPLICATION_ID = "eu.darken.porter";
    /** Activity of Porter's compatibility companion, which installs under Shizuku's package. */
    private static final String COMPANION_MARKER = "eu.darken.porter.compat.OpenPorterActivity";
    private static final String PREFERENCES = "porter.client";
    private static final String BACKEND = "backend";

    public enum Backend { AUTO, PORTER, SHIZUKU }

    /**
     * Which app provides a backend on this device.
     *
     * <p>Porter Compatibility and Shizuku share the {@code moe.shizuku.privileged.api} identity, so
     * a package presence check cannot tell them apart. Android installs only one of them at a time.
     */
    public enum Manager {
        /** No installed package provides this backend. */
        NONE,
        /** The standalone Porter manager. */
        PORTER,
        /** Porter Compatibility: Shizuku's app identity, backed by Porter's server. */
        PORTER_COMPATIBILITY,
        /** Shizuku, or another app using its identity. */
        SHIZUKU,
        /** Sui provides the API from a Magisk module, with no manager package. */
        SUI,
        /** A package owns the permission but could not be identified. */
        UNKNOWN
    }

    private static Backend activeBackend;
    private static Boolean porterOnly;

    public static Backend getPreferredBackend(Context context) {
        if (isPorterOnly(context)) return Backend.PORTER;
        String value = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getString(BACKEND, Backend.AUTO.name());
        try {
            return Backend.valueOf(value);
        } catch (IllegalArgumentException e) {
            return Backend.AUTO;
        }
    }

    public static synchronized Backend getActiveBackend(Context context) {
        if (activeBackend == null) activeBackend = resolve(context, getPreferredBackend(context));
        return activeBackend;
    }

    /**
     * Resolves {@link Backend#AUTO} against installed packages; never returns {@code AUTO}.
     *
     * @param backend must not be null
     */
    public static Backend resolve(Context context, Backend backend) {
        if (Objects.requireNonNull(backend, "backend") != Backend.AUTO) return backend;
        return getPorterPackage(context) != null ? Backend.PORTER : Backend.SHIZUKU;
    }

    public static boolean setBackendForNextProcess(Context context, Backend backend) {
        if (isPorterOnly(context) && backend != Backend.PORTER) {
            throw new IllegalArgumentException("This app only supports Porter");
        }
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit().putString(BACKEND, backend.name()).commit();
    }

    public static String getPorterPackage(Context context) {
        return permissionOwner(context, PERMISSION);
    }

    /**
     * Package declaring Shizuku's client permission, or {@code null}. Porter Compatibility and
     * Shizuku both answer here; use {@link #getManager} to tell them apart.
     */
    public static String getShizukuPackage(Context context) {
        return permissionOwner(context, ShizukuProvider.PERMISSION);
    }

    /**
     * Identifies the app providing {@code backend}, resolving {@link Backend#AUTO}.
     *
     * <p>Needs {@code <queries>} visibility for both manager packages on Android 11 and newer.
     * Reports a manager that is installed, not one that is running.
     *
     * <p>{@link Manager#UNKNOWN} means this package could not be identified, either because an
     * unrecognized package owns the permission or because it could not be inspected. Do not name
     * or launch it without your own verification.
     *
     * <p>{@link Manager#SUI} is only reported once Sui has initialized in this process, which does
     * not happen while Porter is the active backend. A {@link Manager#NONE} answer for a Shizuku
     * selection therefore does not rule out Sui being available after a restart.
     *
     * @param backend must not be null
     */
    public static Manager getManager(Context context, Backend backend) {
        if (resolve(context, backend) == Backend.PORTER) {
            String owner = getPorterPackage(context);
            if (owner == null) return Manager.NONE;
            return PORTER_APPLICATION_ID.equals(owner) ? Manager.PORTER : Manager.UNKNOWN;
        }

        if (Sui.isSui()) return Manager.SUI;

        String owner = getShizukuPackage(context);
        if (owner == null) return Manager.NONE;
        if (!ShizukuProvider.MANAGER_APPLICATION_ID.equals(owner)) return Manager.UNKNOWN;

        Signature[] companion = signatures(context, owner);
        // Cannot inspect the package at all: report that rather than guessing Shizuku.
        if (companion == null || companion.length == 0) return Manager.UNKNOWN;

        // Porter and its companion ship with one signing identity, which is also how the server
        // recognizes the companion. Comparing the two keeps development builds working.
        Signature[] porter = signatures(context, PORTER_APPLICATION_ID);
        if (porter != null && porter.length != 0) {
            return new HashSet<>(Arrays.asList(porter)).equals(new HashSet<>(Arrays.asList(companion)))
                    ? Manager.PORTER_COMPATIBILITY
                    : Manager.SHIZUKU;
        }

        // Porter is gone, so there is nothing to compare against. Fall back to a component the
        // companion carries under its own namespace. This labels a manager, it does not authenticate
        // one: another app is free to declare the same class name.
        Boolean marker = hasComponent(context, owner, COMPANION_MARKER);
        if (marker == null) return Manager.UNKNOWN;
        return marker ? Manager.PORTER_COMPATIBILITY : Manager.SHIZUKU;
    }

    /** {@code null} when the package could not be inspected, as distinct from a missing component. */
    private static Boolean hasComponent(Context context, String packageName, String className) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(packageName,
                    PackageManager.GET_ACTIVITIES | PackageManager.MATCH_DISABLED_COMPONENTS);
            if (info == null) return null;
            if (info.activities == null) return false;
            for (ActivityInfo activity : info.activities) {
                if (activity != null && className.equals(activity.name)) return true;
            }
            return false;
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return null;
        }
    }

    private static String permissionOwner(Context context, String permission) {
        try {
            return context.getPackageManager().getPermissionInfo(permission, 0).packageName;
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    private static Signature[] signatures(Context context, String packageName) {
        try {
            if (Build.VERSION.SDK_INT >= 28) {
                PackageInfo info = context.getPackageManager()
                        .getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES);
                return info.signingInfo == null ? null : info.signingInfo.getApkContentsSigners();
            }
            return context.getPackageManager()
                    .getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures;
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return null;
        }
    }

    public static synchronized boolean isPorterOnly(Context context) {
        if (porterOnly != null) return porterOnly;
        try {
            android.os.Bundle metadata = context.getPackageManager().getApplicationInfo(
                    context.getPackageName(), PackageManager.GET_META_DATA).metaData;
            porterOnly = metadata != null && metadata.getBoolean(PORTER_ONLY, false);
            return porterOnly;
        } catch (PackageManager.NameNotFoundException e) {
            throw new IllegalStateException("Cannot read this app's Porter configuration", e);
        }
    }

    private PorterClient() {}
}
