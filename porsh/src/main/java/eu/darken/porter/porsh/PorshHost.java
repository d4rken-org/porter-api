package eu.darken.porter.porsh;

import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.util.Log;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class PorshHost {

    private static final String TAG = "PorshHost";

    // libcore/ojluni/src/main/java/java/lang/ProcessImpl.java

    private static byte[] createCBytesForStringArray(String[] array) {
        if (array == null) {
            return null;
        }

        byte[][] bytes = new byte[array.length][];
        int count = bytes.length; // For added NUL bytes
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = array[i].getBytes();
            count += bytes[i].length;
        }
        byte[] block = new byte[count];
        int i = 0;
        for (byte[] arg : bytes) {
            System.arraycopy(arg, 0, block, i, arg.length);
            i += arg.length + 1;
            // No need to write NUL bytes explicitly
        }
        return block;
    }

    private static byte[] createCBytesForString(String s) {
        if (s == null) {
            return null;
        }

        byte[] bytes = s.getBytes();
        byte[] result = new byte[bytes.length + 1];
        System.arraycopy(bytes, 0,
                result, 0,
                bytes.length);
        result[result.length - 1] = (byte) 0;
        return result;
    }

    private static int detachFd(ParcelFileDescriptor pfd) {
        if (pfd == null) {
            return -1;
        }
        return pfd.detachFd();
    }

    private final String[] args;
    private final String[] env;
    private final String dir;
    private final byte tty;
    private final int stdin;
    private final int stdout;
    private final int stderr;
    private final CountDownLatch exited = new CountDownLatch(1);
    private int pid;
    private int ptmx;
    private volatile int exitCode = Integer.MAX_VALUE;
    private volatile long exitedAtMillis;

    public PorshHost(
            String[] args, String[] env, String dir,
            byte tty,
            ParcelFileDescriptor stdin, ParcelFileDescriptor stdout, ParcelFileDescriptor stderr) {

        this.args = args;
        this.env = env;
        this.dir = dir;
        this.tty = tty;
        this.stdin = detachFd(stdin);
        this.stdout = detachFd(stdout);
        this.stderr = detachFd(stderr);
    }

    /**
     * Fork and execute, start transfer threads.
     */
    public void start() {
        Log.d(TAG, "start");


        byte[] argBlock = createCBytesForStringArray(args);
        byte[] envBlock = createCBytesForStringArray(env);
        byte[] dirBlock = createCBytesForString(dir);

        int[] result = start(
                argBlock, args.length,
                envBlock, env != null ? env.length : -1,
                dirBlock,
                tty, stdin, stdout, stderr);

        pid = result[0];
        ptmx = result[1];

        new Thread(() -> onExited(waitFor(pid))).start();
    }

    public int getPid() {
        return pid;
    }

    void onExited(int code) {
        exitCode = code;
        exitedAtMillis = SystemClock.elapsedRealtime();
        exited.countDown();
    }

    boolean hasExited() {
        return exited.getCount() == 0;
    }

    int getExitCode() {
        return exitCode;
    }

    long getExitedAtMillis() {
        return exitedAtMillis;
    }

    int awaitExitCode(long timeoutMillis) {
        try {
            if (!exited.await(timeoutMillis, TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "Timed out waiting for " + pid + " to exit");
                return -1;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
        return exitCode;
    }

    public void setWindowSize(long size) {
        Log.d(TAG, "setWindowSize");

        setWindowSize(ptmx, size);
    }

    private static native int[] start(
            byte[] argBlock, int argc,
            byte[] envBlock, int envc,
            byte[] dirBlock,
            byte tty, int stdin, int stdout, int stderr);

    private static native void setWindowSize(int ptmx, long size);

    private static native int waitFor(int pid);
}
