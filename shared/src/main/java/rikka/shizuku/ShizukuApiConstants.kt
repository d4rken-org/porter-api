package rikka.shizuku

import androidx.annotation.RestrictTo
import androidx.annotation.RestrictTo.Scope.LIBRARY_GROUP_PREFIX

/** The Shizuku wire as upstream spells it: version, descriptor and every Bundle key. */
object ShizukuApiConstants {

    const val SERVER_VERSION: Int = 13
    const val SERVER_PATCH_VERSION: Int = 6

    // binder
    const val BINDER_DESCRIPTOR: String = "moe.shizuku.server.IShizukuService"
    const val BINDER_TRANSACTION_transact: Int = 1

    // user service
    @RestrictTo(LIBRARY_GROUP_PREFIX)
    const val USER_SERVICE_TRANSACTION_destroy: Int = 16777115

    const val USER_SERVICE_ARG_TAG: String = "shizuku:user-service-arg-tag"
    const val USER_SERVICE_ARG_COMPONENT: String = "shizuku:user-service-arg-component"
    const val USER_SERVICE_ARG_DEBUGGABLE: String = "shizuku:user-service-arg-debuggable"
    const val USER_SERVICE_ARG_VERSION_CODE: String = "shizuku:user-service-arg-version-code"
    const val USER_SERVICE_ARG_PROCESS_NAME: String = "shizuku:user-service-arg-process-name"
    const val USER_SERVICE_ARG_NO_CREATE: String = "shizuku:user-service-arg-no-create"
    const val USER_SERVICE_ARG_DAEMON: String = "shizuku:user-service-arg-daemon"
    const val USER_SERVICE_ARG_USE_32_BIT_APP_PROCESS: String = "shizuku:user-service-arg-use-32-bit-app-process"
    const val USER_SERVICE_ARG_REMOVE: String = "shizuku:user-service-remove"

    @RestrictTo(LIBRARY_GROUP_PREFIX)
    const val USER_SERVICE_ARG_TOKEN: String = "shizuku:user-service-arg-token"

    // bind application
    const val BIND_APPLICATION_SERVER_VERSION: String = "shizuku:attach-reply-version"
    const val BIND_APPLICATION_SERVER_PATCH_VERSION: String = "shizuku:attach-reply-patch-version"
    const val BIND_APPLICATION_SERVER_UID: String = "shizuku:attach-reply-uid"
    const val BIND_APPLICATION_SERVER_SECONTEXT: String = "shizuku:attach-reply-secontext"
    const val BIND_APPLICATION_PERMISSION_GRANTED: String = "shizuku:attach-reply-permission-granted"
    const val BIND_APPLICATION_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE: String =
        "shizuku:attach-reply-should-show-request-permission-rationale"

    // request permission
    const val REQUEST_PERMISSION_REPLY_ALLOWED: String = "shizuku:request-permission-reply-allowed"
    const val REQUEST_PERMISSION_REPLY_IS_ONETIME: String = "shizuku:request-permission-reply-is-onetime"

    // attach application
    const val ATTACH_APPLICATION_PACKAGE_NAME: String = "shizuku:attach-package-name"
    const val ATTACH_APPLICATION_API_VERSION: String = "shizuku:attach-api-version"
}
