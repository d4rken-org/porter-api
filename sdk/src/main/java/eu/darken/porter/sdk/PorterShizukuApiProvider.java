package eu.darken.porter.sdk;

import android.content.Context;
import android.content.pm.ProviderInfo;

import androidx.annotation.NonNull;

/**
 * Receives the binder a Shizuku server sends, which travels in a different envelope at a different
 * authority than {@link PorterApiProvider}'s.
 *
 * <p><b>User services are not available on this backend yet.</b> A binder delivered here attaches
 * and {@link Porter} answers for it, but {@link Porter#bindUserService},
 * {@link Porter#peekUserService} and {@link Porter#unbindUserService} throw
 * {@link UnsupportedOperationException}.
 *
 * <p>The SDK declares nothing at that authority. An app that wants Shizuku delivery adds the whole
 * block itself, the permission and the meta-data included: the server refuses an app that requests
 * neither.
 *
 * <pre class="prettyprint">&lt;uses-permission android:name="moe.shizuku.manager.permission.API_V23" /&gt;
 *
 *&lt;application&gt;
 *    &lt;meta-data android:name="moe.shizuku.client.V3_SUPPORT" android:value="true" /&gt;
 *    &lt;provider
 *        android:name="eu.darken.porter.sdk.PorterShizukuApiProvider"
 *        android:authorities="${applicationId}.shizuku"
 *        android:exported="true"
 *        android:multiprocess="false"
 *        android:permission="android.permission.INTERACT_ACROSS_USERS_FULL" /&gt;
 *&lt;/application&gt;</pre>
 *
 * <p>This envelope needs the optional {@code com.github.d4rken-org.porter-api:shizuku-compat}
 * artifact, which the SDK does not depend on. Declaring the provider without it throws at attach.
 */
public class PorterShizukuApiProvider extends PorterApiProvider {

    @Override
    public void attachInfo(Context context, ProviderInfo info) {
        if (!ShizukuCompat.isPresent()) {
            throw new IllegalStateException("shizuku-compat is not on the classpath");
        }

        super.attachInfo(context, info);
    }

    @NonNull
    @Override
    PorterDelivery delivery() {
        return ShizukuProtocolDelivery.INSTANCE;
    }
}
