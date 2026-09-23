package eu.darken.porter.sdk.extras.internal;

interface IPorterShellProcess {

    // Each stream is handed out once.
    ParcelFileDescriptor takeStdin() = 1;

    ParcelFileDescriptor takeStdout() = 2;

    ParcelFileDescriptor takeStderr() = 3;

    // Whether the process exited within the timeout.
    boolean waitFor(long timeoutMillis) = 4;

    boolean alive() = 5;

    // Throws IllegalStateException while the process runs.
    int exitValue() = 6;

    void destroy() = 7;
}
