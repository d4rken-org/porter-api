package eu.darken.porter.server;

interface IPorterRemoteProcess {

    ParcelFileDescriptor getOutputStream() = 1;

    ParcelFileDescriptor getInputStream() = 2;

    ParcelFileDescriptor getErrorStream() = 3;

    int waitFor() = 4;

    int exitValue() = 5;

    void destroy() = 6;

    boolean alive() = 7;

    boolean waitForTimeout(long timeout, String unit) = 8;
}
