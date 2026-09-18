package eu.darken.porter.sdk;

/** The wire a Shizuku server speaks: descriptors, raw codes and Bundle keys. */
final class ShizukuProtocol {

    private ShizukuProtocol() {
    }

    /**
     * Written by hand rather than taken from a generated stub: the modules holding the Shizuku AIDL
     * and its constants are not published, so nothing an integrator resolves would carry them.
     */
    static final String DESCRIPTOR = "moe.shizuku.server.IShizukuService";
    static final String APPLICATION_DESCRIPTOR = "moe.shizuku.server.IShizukuApplication";
    static final String SERVICE_CONNECTION_DESCRIPTOR = "moe.shizuku.server.IShizukuServiceConnection";

    /** What attach declares this client to be. */
    static final int CLIENT_API_VERSION = 13;
    /** A server below this is refused rather than spoken to on an older encoding. */
    static final int MINIMUM_VERSION = 13;

    // Codes on DESCRIPTOR. The trailing comment is the id the method declares in the AIDL; the
    // generated stub numbers it FIRST_CALL_TRANSACTION above that, and FIRST_CALL_TRANSACTION is 1.
    /** Outside the AIDL, and answered ahead of the generated dispatch. */
    static final int TRANSACTION_transactRemote = 1;
    static final int TRANSACTION_getUid = 4; // 3
    static final int TRANSACTION_checkPermission = 5; // 4
    static final int TRANSACTION_getSELinuxContext = 9; // 8
    static final int TRANSACTION_getSystemProperty = 10; // 9
    static final int TRANSACTION_setSystemProperty = 11; // 10
    static final int TRANSACTION_addUserService = 12; // 11
    static final int TRANSACTION_removeUserService = 13; // 12
    static final int TRANSACTION_requestPermission = 15; // 14
    static final int TRANSACTION_checkSelfPermission = 16; // 15
    static final int TRANSACTION_shouldShowRequestPermissionRationale = 17; // 16
    static final int TRANSACTION_attachApplication = 18; // 17
    static final int TRANSACTION_exit = 101; // 100
    static final int TRANSACTION_attachUserService = 102; // 101
    static final int TRANSACTION_dispatchPermissionConfirmationResult = 105; // 104
    static final int TRANSACTION_getFlagsForUid = 106; // 105
    static final int TRANSACTION_updateFlagsForUid = 107; // 106

    // Codes on APPLICATION_DESCRIPTOR, which the server transacts on the client.
    static final int APPLICATION_TRANSACTION_bindApplication = 2; // 1
    static final int APPLICATION_TRANSACTION_dispatchRequestPermissionResult = 3; // 2

    // Codes on SERVICE_CONNECTION_DESCRIPTOR, which the server transacts on the client.
    static final int SERVICE_CONNECTION_TRANSACTION_connected = 1; // 0
    static final int SERVICE_CONNECTION_TRANSACTION_died = 2; // 1

    // attach args
    static final String ATTACH_APPLICATION_PACKAGE_NAME = "shizuku:attach-package-name";
    static final String ATTACH_APPLICATION_API_VERSION = "shizuku:attach-api-version";

    // bind application, which is where the attach reply arrives
    static final String BIND_APPLICATION_SERVER_UID = "shizuku:attach-reply-uid";
    static final String BIND_APPLICATION_SERVER_VERSION = "shizuku:attach-reply-version";
    static final String BIND_APPLICATION_SERVER_PATCH_VERSION = "shizuku:attach-reply-patch-version";
    static final String BIND_APPLICATION_SERVER_SECONTEXT = "shizuku:attach-reply-secontext";
    static final String BIND_APPLICATION_PERMISSION_GRANTED = "shizuku:attach-reply-permission-granted";
    static final String BIND_APPLICATION_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE =
            "shizuku:attach-reply-should-show-request-permission-rationale";

    // permission result, and the confirmation a manager sends back
    static final String REQUEST_PERMISSION_REPLY_ALLOWED = "shizuku:request-permission-reply-allowed";
    static final String REQUEST_PERMISSION_REPLY_IS_ONETIME = "shizuku:request-permission-reply-is-onetime";

    // user service
    static final String USER_SERVICE_ARG_TAG = "shizuku:user-service-arg-tag";
    static final String USER_SERVICE_ARG_COMPONENT = "shizuku:user-service-arg-component";
    static final String USER_SERVICE_ARG_DEBUGGABLE = "shizuku:user-service-arg-debuggable";
    static final String USER_SERVICE_ARG_VERSION_CODE = "shizuku:user-service-arg-version-code";
    static final String USER_SERVICE_ARG_PROCESS_NAME = "shizuku:user-service-arg-process-name";
    static final String USER_SERVICE_ARG_NO_CREATE = "shizuku:user-service-arg-no-create";
    static final String USER_SERVICE_ARG_DAEMON = "shizuku:user-service-arg-daemon";
    static final String USER_SERVICE_ARG_USE_32_BIT_APP_PROCESS = "shizuku:user-service-arg-use-32-bit-app-process";
    /** Spelled without the "-arg-" of the other eight; {@code ShizukuProtocolConstantsTest} pins it. */
    static final String USER_SERVICE_ARG_REMOVE = "shizuku:user-service-remove";
    static final String USER_SERVICE_ARG_TOKEN = "shizuku:user-service-arg-token";
}
