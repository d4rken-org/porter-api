package eu.darken.porter.core;

import android.os.Binder;

import rikka.shizuku.server.util.UserHandleCompat;

/**
 * Who made a call. Captured once at the endpoint the call arrived on and handed down from there, so
 * that nothing below the endpoint has to ask Binder again.
 */
public final class CallerIdentity {

    public final int uid;
    public final int pid;

    public CallerIdentity(int uid, int pid) {
        this.uid = uid;
        this.pid = pid;
    }

    public static CallerIdentity fromBinder() {
        return new CallerIdentity(Binder.getCallingUid(), Binder.getCallingPid());
    }

    public int userId() {
        return UserHandleCompat.getUserId(uid);
    }

    public int appId() {
        return UserHandleCompat.getAppId(uid);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof CallerIdentity)) return false;
        CallerIdentity other = (CallerIdentity) obj;
        return uid == other.uid && pid == other.pid;
    }

    @Override
    public int hashCode() {
        return 31 * uid + pid;
    }

    @Override
    public String toString() {
        return "CallerIdentity{uid=" + uid + ", pid=" + pid + "}";
    }
}
