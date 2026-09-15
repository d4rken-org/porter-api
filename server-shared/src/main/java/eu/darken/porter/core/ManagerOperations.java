package eu.darken.porter.core;

import android.os.IBinder;

/**
 * The manager-only operations the server application answers, reached from either endpoint once the
 * manager gate passed. An implementation that must be atomic against record creation synchronizes
 * on {@code core.getClientManager()}, the monitor {@link PorterCore#attach} takes.
 */
public interface ManagerOperations {

    void exit();

    void attachUserService(IBinder binder, String token);

    void dispatchPermissionConfirmationResult(
            int requestUid, int requestPid, int requestCode, boolean allowed, boolean onetime);

    int getFlagsForUid(int uid, int mask);

    void updateFlagsForUid(int uid, int mask, int value);
}
