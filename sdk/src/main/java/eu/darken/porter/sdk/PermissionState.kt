package eu.darken.porter.sdk

/** Whether the server lets this app through, as the server last reported it. */
public sealed interface PermissionState {

    public data object Granted : PermissionState

    /**
     * @param shouldShowRationale true when the user chose "deny and don't ask again", so a request
     * would be refused without a prompt
     */
    public data class Denied(val shouldShowRationale: Boolean) : PermissionState
}

public val PermissionState.isGranted: Boolean
    get() = this is PermissionState.Granted
