package eu.darken.porter.sdk;

import android.os.Bundle;

/**
 * Reaches the SDK's user service encoding, which is package-private, from a test that decodes it
 * with the server's own decoder and so cannot live in the SDK's module.
 */
public final class UserServiceArgsBridge {

    private UserServiceArgsBridge() {
    }

    public static Bundle forAdd(Porter.UserServiceArgs args) {
        return PorterUserServiceCodec.encodeUserService(args, false);
    }

    public static Bundle forRemove(Porter.UserServiceArgs args, boolean remove) {
        return PorterUserServiceCodec.encodeUserServiceRemoval(args, remove);
    }
}
