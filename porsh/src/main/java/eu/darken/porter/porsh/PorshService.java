package eu.darken.porter.porsh;

import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.system.Os;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public abstract class PorshService {

    private static final String TAG = "PorshService";

    private static final boolean IS_ROOT = Os.getuid() == 0;

    private final PorshHostRegistry registry;

    public PorshService() {
        this(new PorshHostRegistry(PorshHost::new, SystemClock::elapsedRealtime));
    }

    PorshService(PorshHostRegistry registry) {
        this.registry = registry;
    }

    private void createHost(
            String[] args, String[] env, String dir,
            byte tty,
            ParcelFileDescriptor stdin, ParcelFileDescriptor stdout, ParcelFileDescriptor stderr) {

        int callingPid = Binder.getCallingPid();

        // Termux app set PATH and LD_PRELOAD to Termux's internal path.
        // Adb does not have sufficient permissions to access such places.

        // Under adb, users need to set RISH_PRESERVE_ENV=1 to preserve env.
        // Under root, keep env unless RISH_PRESERVE_ENV=0 is set.

        boolean allowEnv = IS_ROOT;
        for (String e : env) {
            if ("RISH_PRESERVE_ENV=1".equals(e)) {
                allowEnv = true;
                break;
            } else if ("RISH_PRESERVE_ENV=0".equals(e)) {
                allowEnv = false;
                break;
            }
        }
        if (!allowEnv) {
            env = null;
        }

        registry.createHost(callingPid, args, env, dir, tty, stdin, stdout, stderr);
    }

    private void setWindowSize(long size) {
        registry.setWindowSize(Binder.getCallingPid(), size);
    }

    private int getExitCode() {
        return registry.getExitCode(Binder.getCallingPid());
    }

    public abstract void enforceCallingPermission(String func);

    public boolean onTransact(int code, @NonNull Parcel data, @Nullable Parcel reply, int flags) {
        if (code == PorshConfig.getTransactionCode(PorshConfig.TRANSACTION_createHost)) {
            Log.d(TAG, "TRANSACTION_createHost");

            enforceCallingPermission("createHost");

            if (reply == null || (flags & IBinder.FLAG_ONEWAY) != 0) {
                return true;
            }

            ParcelFileDescriptor stdin;
            ParcelFileDescriptor stdout;
            ParcelFileDescriptor stderr = null;

            data.enforceInterface(PorshConfig.getInterfaceToken());
            byte tty = data.readByte();
            stdin = data.readFileDescriptor();
            stdout = data.readFileDescriptor();
            if ((tty & PorshConstants.ATTY_ERR) == 0) {
                stderr = data.readFileDescriptor();
            }
            String[] args = data.createStringArray();
            String[] env = data.createStringArray();
            String dir = data.readString();
            createHost(args, env, dir, tty, stdin, stdout, stderr);
            reply.writeNoException();
            return true;
        } else if (code == PorshConfig.getTransactionCode(PorshConfig.TRANSACTION_setWindowSize)) {
            Log.d(TAG, "TRANSACTION_setWindowSize");

            enforceCallingPermission("setWindowSize");

            data.enforceInterface(PorshConfig.getInterfaceToken());
            long size = data.readLong();
            setWindowSize(size);
            if (reply != null) {
                reply.writeNoException();
            }
            return true;
        } else if (code == PorshConfig.getTransactionCode(PorshConfig.TRANSACTION_getExitCode)) {
            Log.d(TAG, "TRANSACTION_getExitCode");

            enforceCallingPermission("getExitCode");

            data.enforceInterface(PorshConfig.getInterfaceToken());
            int exitCode = getExitCode();
            if (reply != null) {
                reply.writeNoException();
                reply.writeInt(exitCode);
            }
            return true;
        }
        return false;
    }

}
