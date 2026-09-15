package rikka.shizuku.server.api;

import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;

import java.util.concurrent.TimeUnit;

import eu.darken.porter.core.ServerProcess;
import moe.shizuku.server.IRemoteProcess;

public class RemoteProcessHolder extends IRemoteProcess.Stub {

    private final ServerProcess process;

    public RemoteProcessHolder(ServerProcess process) {
        this.process = process;
    }

    public RemoteProcessHolder(Process process, IBinder token) {
        this(new ServerProcess(process, token));
    }

    @Override
    public ParcelFileDescriptor getOutputStream() {
        return process.getOutputStream();
    }

    @Override
    public ParcelFileDescriptor getInputStream() {
        return process.getInputStream();
    }

    @Override
    public ParcelFileDescriptor getErrorStream() {
        return process.getErrorStream();
    }

    @Override
    public int waitFor() {
        return process.waitFor();
    }

    @Override
    public int exitValue() {
        return process.exitValue();
    }

    @Override
    public void destroy() {
        process.destroy();
    }

    @Override
    public boolean alive() throws RemoteException {
        return process.alive();
    }

    @Override
    public boolean waitForTimeout(long timeout, String unitName) throws RemoteException {
        // Parsed here so an unknown name is still an IllegalArgumentException across the binder.
        return process.waitForTimeout(timeout, TimeUnit.valueOf(unitName));
    }
}
