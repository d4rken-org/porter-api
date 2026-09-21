package eu.darken.porter.sdk

/**
 * The wire protocol spoken on a connection, which is not the product the server belongs to: a
 * [SHIZUKU] connection is one speaking Shizuku's protocol, whoever the peer is.
 */
public enum class PorterBackend {
    PORTER,
    SHIZUKU,
}
