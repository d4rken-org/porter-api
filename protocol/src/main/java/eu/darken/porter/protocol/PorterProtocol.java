package eu.darken.porter.protocol;

/** The wire Porter's own endpoint speaks: descriptor, version, raw codes and Bundle keys. */
public final class PorterProtocol {

    private PorterProtocol() {
    }

    /** Must equal the descriptor of the generated {@code IPorterService.Stub}. */
    public static final String DESCRIPTOR = "eu.darken.porter.server.IPorterService";

    /**
     * The protocol version both sides send. Independent of the SDK release version and of
     * {@code ShizukuApiConstants.SERVER_VERSION}.
     */
    public static final int VERSION = 3;

    // Raw codes on DESCRIPTOR, outside the range the AIDL ids occupy.
    public static final int TRANSACTION_transactRemote = 100;
    /** createHost 200, setWindowSize 201, getExitCode 202. */
    public static final int TRANSACTION_PORSH_BASE = 200;
    /**
     * Codes at or above this on {@link #DESCRIPTOR} belong to the server application's own
     * operations. This protocol never allocates one of them.
     */
    public static final int TRANSACTION_APP_BASE = 10000;

    // attach args
    public static final String ATTACH_PACKAGE_NAME = "porter:attach-package-name";
    public static final String ATTACH_PROTOCOL_VERSION = "porter:attach-protocol-version";

    // attach reply
    public static final String REPLY_PROTOCOL_VERSION = "porter:reply-protocol-version";
    public static final String REPLY_SERVER_UID = "porter:reply-server-uid";
    public static final String REPLY_SERVER_SECONTEXT = "porter:reply-server-secontext";
    public static final String REPLY_PERMISSION_GRANTED = "porter:reply-permission-granted";
    public static final String REPLY_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE =
            "porter:reply-should-show-request-permission-rationale";
    /**
     * Bitmask. A bit is allocated when the capability ships and is never reused, so an SDK that
     * does not know a bit ignores it.
     */
    public static final String REPLY_CAPABILITIES = "porter:reply-capabilities";
    public static final long CAPABILITIES_NONE = 0L;

    // permission result
    public static final String PERMISSION_RESULT_ALLOWED = "porter:permission-result-allowed";

    // permission confirmation, manager to server
    public static final String PERMISSION_CONFIRMATION_ALLOWED = "porter:permission-confirmation-allowed";
    public static final String PERMISSION_CONFIRMATION_ONETIME = "porter:permission-confirmation-onetime";

    // user service args
    public static final String USER_SERVICE_COMPONENT = "porter:user-service-component";
    public static final String USER_SERVICE_TAG = "porter:user-service-tag";
    public static final String USER_SERVICE_VERSION_CODE = "porter:user-service-version-code";
    public static final String USER_SERVICE_PROCESS_NAME_SUFFIX = "porter:user-service-process-name-suffix";
    public static final String USER_SERVICE_DEBUGGABLE = "porter:user-service-debuggable";
    public static final String USER_SERVICE_NO_CREATE = "porter:user-service-no-create";
    public static final String USER_SERVICE_DAEMON = "porter:user-service-daemon";
    public static final String USER_SERVICE_USE_32_BIT = "porter:user-service-use-32-bit";
    public static final String USER_SERVICE_REMOVE = "porter:user-service-remove";
    /** Names the record a user service host reports back for. */
    public static final String USER_SERVICE_TOKEN = "porter:user-service-token";

    // binder delivery
    public static final String PROVIDER_AUTHORITY_SUFFIX = ".porter.api";
    public static final String DELIVERY_METHOD_SEND_BINDER = "sendBinder";
    public static final String DELIVERY_METHOD_GET_BINDER = "getBinder";
    /**
     * A user service host hands its binder and {@link #USER_SERVICE_TOKEN} to the manager, which
     * calls {@code attachUserService} with them.
     */
    public static final String DELIVERY_METHOD_SEND_USER_SERVICE = "sendUserService";
    /** Carried by {@code Bundle.putBinder}/{@code getBinder}, not by a Parcelable container. */
    public static final String DELIVERY_EXTRA_BINDER = "eu.darken.porter.server.extra.BINDER";

    // permission flags, as getFlagsForUid and updateFlagsForUid speak them
    public static final int FLAG_ALLOWED = 1 << 1;
    public static final int FLAG_DENIED = 1 << 2;
    public static final int MASK_PERMISSION = FLAG_ALLOWED | FLAG_DENIED;

    public static final String PERMISSION = "eu.darken.porter.permission.API_V23";
    public static final String MANAGER_APPLICATION_ID = "eu.darken.porter";
}
