package eu.darken.porter.protocol

/** The wire Porter's own endpoint speaks: descriptor, version, raw codes and Bundle keys. */
public object PorterProtocol {

    /** Must equal the descriptor of the generated `IPorterService.Stub`. */
    public const val DESCRIPTOR: String = "eu.darken.porter.server.IPorterService"

    /**
     * The protocol version both sides send. Independent of the SDK release version and of
     * `ShizukuApiConstants.SERVER_VERSION`.
     */
    public const val VERSION: Int = 3

    // Raw codes on DESCRIPTOR, outside the range the AIDL ids occupy.
    public const val TRANSACTION_transactRemote: Int = 100

    /** createHost 200, setWindowSize 201, getExitCode 202. */
    public const val TRANSACTION_PORSH_BASE: Int = 200

    /**
     * Codes at or above this on [DESCRIPTOR] belong to the server application's own operations.
     * This protocol never allocates one of them.
     */
    public const val TRANSACTION_APP_BASE: Int = 10000

    // attach args
    public const val ATTACH_PACKAGE_NAME: String = "porter:attach-package-name"
    public const val ATTACH_PROTOCOL_VERSION: String = "porter:attach-protocol-version"

    // attach reply
    public const val REPLY_PROTOCOL_VERSION: String = "porter:reply-protocol-version"
    public const val REPLY_SERVER_UID: String = "porter:reply-server-uid"
    public const val REPLY_SERVER_SECONTEXT: String = "porter:reply-server-secontext"
    public const val REPLY_PERMISSION_GRANTED: String = "porter:reply-permission-granted"
    public const val REPLY_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE: String =
        "porter:reply-should-show-request-permission-rationale"

    /**
     * Bitmask. A bit is allocated when the capability ships and is never reused, so an SDK that
     * does not know a bit ignores it.
     */
    public const val REPLY_CAPABILITIES: String = "porter:reply-capabilities"
    public const val CAPABILITIES_NONE: Long = 0L

    // permission result
    public const val PERMISSION_RESULT_ALLOWED: String = "porter:permission-result-allowed"

    // permission confirmation, manager to server
    public const val PERMISSION_CONFIRMATION_ALLOWED: String = "porter:permission-confirmation-allowed"
    public const val PERMISSION_CONFIRMATION_ONETIME: String = "porter:permission-confirmation-onetime"

    // user service args
    public const val USER_SERVICE_COMPONENT: String = "porter:user-service-component"
    public const val USER_SERVICE_TAG: String = "porter:user-service-tag"
    public const val USER_SERVICE_VERSION_CODE: String = "porter:user-service-version-code"
    public const val USER_SERVICE_PROCESS_NAME_SUFFIX: String = "porter:user-service-process-name-suffix"
    public const val USER_SERVICE_DEBUGGABLE: String = "porter:user-service-debuggable"
    public const val USER_SERVICE_NO_CREATE: String = "porter:user-service-no-create"
    public const val USER_SERVICE_DAEMON: String = "porter:user-service-daemon"
    public const val USER_SERVICE_USE_32_BIT: String = "porter:user-service-use-32-bit"
    public const val USER_SERVICE_REMOVE: String = "porter:user-service-remove"

    /** Names the record a user service host reports back for. */
    public const val USER_SERVICE_TOKEN: String = "porter:user-service-token"

    // binder delivery
    public const val PROVIDER_AUTHORITY_SUFFIX: String = ".porter.api"
    public const val DELIVERY_METHOD_SEND_BINDER: String = "sendBinder"
    public const val DELIVERY_METHOD_GET_BINDER: String = "getBinder"

    /**
     * A user service host hands its binder and [USER_SERVICE_TOKEN] to the manager, which calls
     * `attachUserService` with them.
     */
    public const val DELIVERY_METHOD_SEND_USER_SERVICE: String = "sendUserService"

    /** Carried by `Bundle.putBinder`/`getBinder`, not by a Parcelable container. */
    public const val DELIVERY_EXTRA_BINDER: String = "eu.darken.porter.server.extra.BINDER"

    // permission flags, as getFlagsForUid and updateFlagsForUid speak them
    public const val FLAG_ALLOWED: Int = 1 shl 1
    public const val FLAG_DENIED: Int = 1 shl 2
    public const val MASK_PERMISSION: Int = FLAG_ALLOWED or FLAG_DENIED

    public const val PERMISSION: String = "eu.darken.porter.permission.API_V23"
    public const val MANAGER_APPLICATION_ID: String = "eu.darken.porter"
}
