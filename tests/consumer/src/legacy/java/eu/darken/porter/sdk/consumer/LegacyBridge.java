package eu.darken.porter.sdk.consumer;

import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuProvider;

/** Compiles only in the dual-SDK build: both SDKs on one classpath, sharing no class. */
public class LegacyBridge {
    public boolean shizukuAlive() {
        return Shizuku.pingBinder();
    }

    public String shizukuManager() {
        return ShizukuProvider.MANAGER_APPLICATION_ID;
    }
}
