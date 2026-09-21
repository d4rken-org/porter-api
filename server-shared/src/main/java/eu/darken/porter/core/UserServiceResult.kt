package eu.darken.porter.core

/** What a bind established, before an endpoint encodes it in its own wire's integers. */
sealed class UserServiceBindResult {

    /** The connection is registered; the binder arrives on it once the service reports in. */
    object Bound : UserServiceBindResult()

    /** A no-create bind found a live service and registered the connection with it. */
    class Running(val versionCode: Int) : UserServiceBindResult()

    /** A no-create bind found nothing to bind to. */
    object NotRunning : UserServiceBindResult()
}

/** What a removal did, before an endpoint encodes it in its own wire's integers. */
sealed class UserServiceRemoveResult {

    /** The connection was unregistered, or the record removed when that was asked. */
    object Removed : UserServiceRemoveResult()

    /** No record carries the identity the caller named. */
    object NoSuchRecord : UserServiceRemoveResult()
}
