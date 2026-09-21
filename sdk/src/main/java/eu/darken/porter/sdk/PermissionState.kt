package eu.darken.porter.sdk

/** Whether the server lets this app through, as the server last reported it. */
public sealed interface PermissionState {

    public data object Granted : PermissionState

    /**
     * @param permanentlyDenied true when the user chose "deny and don't ask again", so a request
     * would be refused without a prompt. The user can still change that in the Porter app.
     */
    public data class Denied(val permanentlyDenied: Boolean) : PermissionState
}

public val PermissionState.isGranted: Boolean
    get() = this is PermissionState.Granted
