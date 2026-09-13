package eu.darken.porter.porsh;

import android.annotation.SuppressLint;
import android.os.IBinder;
import android.util.Log;

public class PorshConfig {

    private static final String TAG = "PORSHConfig";

    static final int TRANSACTION_createHost = 0;
    static final int TRANSACTION_setWindowSize = 1;
    static final int TRANSACTION_getExitCode = 2;

    private static IBinder binder;
    private static String interfaceToken;
    private static int transactionCodeStart;
    private static String libraryPath;

    static IBinder getBinder() {
        return binder;
    }

    static String getInterfaceToken() {
        return interfaceToken;
    }

    static int getTransactionCode(int code) {
        return transactionCodeStart + code;
    }

    public static void setLibraryPath(String path) {
        libraryPath = path;
    }

    @SuppressLint("UnsafeDynamicallyLoadedCode")
    private static void loadLibrary() {
        if (libraryPath == null) {
            System.loadLibrary("porsh");
        } else {
            System.load(libraryPath + "/libporsh.so");
        }
    }

    public static void init(String interfaceToken, int transactionCodeStart) {
        Log.d(TAG, "init (server) " + interfaceToken + " " + transactionCodeStart);
        PorshConfig.interfaceToken = interfaceToken;
        PorshConfig.transactionCodeStart = transactionCodeStart;
        loadLibrary();
    }

    public static void init(IBinder binder, String interfaceToken, int transactionCodeStart) {
        Log.d(TAG, "init (client) " + binder + " " + interfaceToken + " " + transactionCodeStart);
        PorshConfig.binder = binder;
        PorshConfig.interfaceToken = interfaceToken;
        PorshConfig.transactionCodeStart = transactionCodeStart;
        loadLibrary();
    }
}
