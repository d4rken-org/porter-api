package eu.darken.porter.endpoint;

import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;

import java.util.concurrent.TimeUnit;

import eu.darken.porter.core.ServerProcess;
import eu.darken.porter.server.IPorterRemoteProcess;

public class PorterRemoteProcessHolder extends IPorterRemoteProcess.Stub {

    private final ServerProcess process;

    public PorterRemoteProcessHolder(ServerProcess process) {
        this.process = process;
    }

    public PorterRemoteProcessHolder(Process process, IBinder token) {
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
