package eu.darken.porter.protocol

/**
 * The wire Porter's own endpoint speaks: descriptor, version, raw codes and Bundle keys.
 *
 * Transaction codes on [DESCRIPTOR] are allocated in four ranges. The AIDL ids of
 * `IPorterService` stay below 100; the raw codes this object names occupy 100 to 199; the
 * terminal (porsh) codes occupy 200 to 299; everything from [TRANSACTION_APP_BASE] up belongs to
 * the server application and is never allocated here.
 */
public object PorterProtocol {

    /** Must equal the descriptor of the generated `IPorterService.Stub`. */
    public const val DESCRIPTOR: String = "eu.darken.porter.server.IPorterService"

    /**
     * The protocol version both sides send. Independent of the SDK release version and of
     * `ShizukuApiConstants.SERVER_VERSION`.
     *
     * Versions describe cumulative compatibility: a peer at a higher version still speaks every
     * version from its floor up, so a newer peer is never inherently incompatible. A side raises
     * its floor ([MIN_VERSION] here, the SDK's own floor for servers) only when it stops speaking
     * an older generation. Optional functionality is announced through [REPLY_CAPABILITIES], not
     * through the version.
     */
    public const val VERSION: Int = 4

    /** The oldest client version a server at [VERSION] still accepts. */
    public const val MIN_VERSION: Int = 4

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

    /** The oldest client version the replying server accepts; its [MIN_VERSION]. */
    public const val REPLY_MIN_PROTOCOL_VERSION: String = "porter:reply-min-protocol-version"

    /**
     * True on the reply to an attach the server refused for its version. Such a reply carries
     * [REPLY_PROTOCOL_VERSION] and [REPLY_MIN_PROTOCOL_VERSION] and nothing else, and no record
     * was created for the caller.
     */
    public const val REPLY_UNSUPPORTED: String = "porter:reply-unsupported"
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

    // user service results: what addUserService and removeUserService answer
    /** `addUserService`: the connection is registered; the binder arrives on it. */
    public const val USER_SERVICE_RESULT_BOUND: Int = 0

    /** `addUserService` with [USER_SERVICE_NO_CREATE]: nothing is running. Any other non-negative answer is the running service's version code. */
    public const val USER_SERVICE_RESULT_NOT_RUNNING: Int = -1

    /** `removeUserService`: the server holds no record for the service named. */
    public const val USER_SERVICE_RESULT_NO_SUCH_SERVICE: Int = 1

    /**
     * The code the server transacts on a user service binder to ask it to shut down; `16777114`
     * in aidl, as the generated stub numbers from `FIRST_CALL_TRANSACTION`. Same value as the
     * Shizuku wire uses, so one service class serves both.
     */
    public const val USER_SERVICE_TRANSACTION_destroy: Int = 16777115

    // binder delivery
    public const val PROVIDER_AUTHORITY_SUFFIX: String = ".porter.api"
    public const val DELIVERY_METHOD_SEND_BINDER: String = "sendBinder"
    public const val DELIVERY_METHOD_GET_BINDER: String = "getBinder"

    /**
     * A user service host hands its binder and [USER_SERVICE_TOKEN] to the manager, which passes
     * them on to the server.
     */
    public const val DELIVERY_METHOD_SEND_USER_SERVICE: String = "sendUserService"

    /** Carried by `Bundle.putBinder`/`getBinder`, not by a Parcelable container. */
    public const val DELIVERY_EXTRA_BINDER: String = "eu.darken.porter.server.extra.BINDER"

    public const val PERMISSION: String = "eu.darken.porter.permission.API"
    public const val MANAGER_APPLICATION_ID: String = "eu.darken.porter"
}
