package eu.darken.porter.sdk

/** The wire a Shizuku server speaks: descriptors, raw codes and Bundle keys. */
internal object ShizukuProtocol {

    /**
     * Written by hand rather than taken from a generated stub: the modules holding the Shizuku AIDL
     * and its constants are not published, so nothing an integrator resolves would carry them.
     */
    const val DESCRIPTOR: String = "moe.shizuku.server.IShizukuService"
    const val APPLICATION_DESCRIPTOR: String = "moe.shizuku.server.IShizukuApplication"
    const val SERVICE_CONNECTION_DESCRIPTOR: String = "moe.shizuku.server.IShizukuServiceConnection"

    /** The canonical manager, and the permission it declares. */
    const val MANAGER_APPLICATION_ID: String = "moe.shizuku.privileged.api"
    const val PERMISSION: String = "moe.shizuku.manager.permission.API_V23"

    /**
     * Shizuku+'s Plus flavor, which declares this permission instead of [PERMISSION] and delivers
     * its binder the same way.
     */
    const val PLUS_MANAGER_APPLICATION_ID: String = "af.shizuku.plus.api"
    const val PLUS_PERMISSION: String = "af.shizuku.plus.permission.API_V23"

    /** What attach declares this client to be. */
    const val CLIENT_API_VERSION: Int = 13

    /** A server below this is refused rather than spoken to on an older encoding. */
    const val MINIMUM_VERSION: Int = 13

    // Codes on DESCRIPTOR. The trailing comment is the id the method declares in the AIDL; the
    // generated stub numbers it FIRST_CALL_TRANSACTION above that, and FIRST_CALL_TRANSACTION is 1.
    /** Outside the AIDL, and answered ahead of the generated dispatch. */
    const val TRANSACTION_transactRemote: Int = 1
    const val TRANSACTION_getUid: Int = 4 // 3
    const val TRANSACTION_checkPermission: Int = 5 // 4
    const val TRANSACTION_getSELinuxContext: Int = 9 // 8
    const val TRANSACTION_getSystemProperty: Int = 10 // 9
    const val TRANSACTION_setSystemProperty: Int = 11 // 10
    const val TRANSACTION_addUserService: Int = 12 // 11
    const val TRANSACTION_removeUserService: Int = 13 // 12
    const val TRANSACTION_requestPermission: Int = 15 // 14
    const val TRANSACTION_checkSelfPermission: Int = 16 // 15
    const val TRANSACTION_shouldShowRequestPermissionRationale: Int = 17 // 16
    const val TRANSACTION_attachApplication: Int = 18 // 17

    // Codes on APPLICATION_DESCRIPTOR, which the server transacts on the client.
    const val APPLICATION_TRANSACTION_bindApplication: Int = 2 // 1
    const val APPLICATION_TRANSACTION_dispatchRequestPermissionResult: Int = 3 // 2

    // Codes on SERVICE_CONNECTION_DESCRIPTOR, which the server transacts on the client.
    const val SERVICE_CONNECTION_TRANSACTION_connected: Int = 1 // 0
    const val SERVICE_CONNECTION_TRANSACTION_died: Int = 2 // 1

    // attach args
    const val ATTACH_APPLICATION_PACKAGE_NAME: String = "shizuku:attach-package-name"
    const val ATTACH_APPLICATION_API_VERSION: String = "shizuku:attach-api-version"

    // bind application, which is where the attach reply arrives
    const val BIND_APPLICATION_SERVER_UID: String = "shizuku:attach-reply-uid"
    const val BIND_APPLICATION_SERVER_VERSION: String = "shizuku:attach-reply-version"
    const val BIND_APPLICATION_SERVER_PATCH_VERSION: String = "shizuku:attach-reply-patch-version"
    const val BIND_APPLICATION_SERVER_SECONTEXT: String = "shizuku:attach-reply-secontext"
    const val BIND_APPLICATION_PERMISSION_GRANTED: String = "shizuku:attach-reply-permission-granted"
    const val BIND_APPLICATION_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE: String =
        "shizuku:attach-reply-should-show-request-permission-rationale"

    // permission result
    const val REQUEST_PERMISSION_REPLY_ALLOWED: String = "shizuku:request-permission-reply-allowed"

    // user service
    const val USER_SERVICE_ARG_TAG: String = "shizuku:user-service-arg-tag"
    const val USER_SERVICE_ARG_COMPONENT: String = "shizuku:user-service-arg-component"
    const val USER_SERVICE_ARG_DEBUGGABLE: String = "shizuku:user-service-arg-debuggable"
    const val USER_SERVICE_ARG_VERSION_CODE: String = "shizuku:user-service-arg-version-code"
    const val USER_SERVICE_ARG_PROCESS_NAME: String = "shizuku:user-service-arg-process-name"
    const val USER_SERVICE_ARG_NO_CREATE: String = "shizuku:user-service-arg-no-create"
    const val USER_SERVICE_ARG_DAEMON: String = "shizuku:user-service-arg-daemon"
    const val USER_SERVICE_ARG_USE_32_BIT_APP_PROCESS: String = "shizuku:user-service-arg-use-32-bit-app-process"

    /** Spelled without the "-arg-" of the other eight; `ShizukuProtocolConstantsTest` pins it. */
    const val USER_SERVICE_ARG_REMOVE: String = "shizuku:user-service-remove"
}
