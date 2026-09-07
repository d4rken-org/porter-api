package eu.darken.porter.client;

import android.content.Context;
import android.content.pm.PackageManager;

/** Select a backend before connecting; changing an established session requires an app restart. */
public final class PorterClient {
    public static final String PERMISSION = "eu.darken.porter.permission.API_V23";
    public static final String PORTER_ONLY = "eu.darken.porter.client.PORTER_ONLY";
    private static final String PREFERENCES = "porter.client";
    private static final String BACKEND = "backend";

    public enum Backend { AUTO, PORTER, SHIZUKU }

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
        if (activeBackend == null) {
            Backend preferred = getPreferredBackend(context);
            activeBackend = preferred == Backend.AUTO
                    ? (getPorterPackage(context) != null ? Backend.PORTER : Backend.SHIZUKU)
                    : preferred;
        }
        return activeBackend;
    }

    public static boolean setBackendForNextProcess(Context context, Backend backend) {
        if (isPorterOnly(context) && backend != Backend.PORTER) {
            throw new IllegalArgumentException("This app only supports Porter");
        }
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit().putString(BACKEND, backend.name()).commit();
    }

    public static String getPorterPackage(Context context) {
        try {
            return context.getPackageManager().getPermissionInfo(PERMISSION, 0).packageName;
        } catch (PackageManager.NameNotFoundException e) {
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
