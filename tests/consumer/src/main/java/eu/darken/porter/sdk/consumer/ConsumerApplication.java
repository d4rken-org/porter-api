package eu.darken.porter.sdk.consumer;

import android.app.Application;
import android.content.pm.PackageManager;
import eu.darken.porter.client.PorterClient;
import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuBinderWrapper;

public class ConsumerApplication extends Application {
    public PorterClient.Backend selectedBackend() {
        return PorterClient.getActiveBackend(this);
    }

    public boolean hasAccess() {
        return Shizuku.pingBinder()
                && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
    }

    public ShizukuBinderWrapper wrap(android.os.IBinder binder) {
        return new ShizukuBinderWrapper(binder);
    }
}
