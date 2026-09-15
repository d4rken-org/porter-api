package eu.darken.porter.sdk.consumer;

import android.app.Application;
import android.content.pm.PackageManager;
import eu.darken.porter.sdk.Porter;
import eu.darken.porter.sdk.PorterBinderWrapper;

public class ConsumerApplication extends Application {
    public boolean hasAccess() {
        return Porter.pingBinder()
                && Porter.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
    }

    public PorterBinderWrapper wrap(android.os.IBinder binder) {
        return new PorterBinderWrapper(binder);
    }
}
