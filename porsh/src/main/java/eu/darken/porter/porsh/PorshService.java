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

    private final PorshHostRegistry registry;
    private final EnvPolicy envPolicy;

    public PorshService() {
        this(
                new PorshHostRegistry(PorshHost::new, SystemClock::elapsedRealtime),
                new EnvPolicy(Os.getuid() == 0));
    }

    PorshService(PorshHostRegistry registry, EnvPolicy envPolicy) {
        this.registry = registry;
        this.envPolicy = envPolicy;
    }

    private void createHost(
            String[] args, String[] env, String dir,
            byte tty,
            ParcelFileDescriptor stdin, ParcelFileDescriptor stdout, ParcelFileDescriptor stderr) {

        int callingPid = Binder.getCallingPid();

        registry.createHost(callingPid, args, envPolicy.resolve(env), dir, tty, stdin, stdout, stderr);
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
