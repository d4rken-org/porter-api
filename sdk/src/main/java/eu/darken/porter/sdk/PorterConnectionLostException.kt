package eu.darken.porter.sdk

/** The connection a call was waiting on was replaced or died before the server answered. */
public class PorterConnectionLostException internal constructor() :
    PorterException("the Porter connection was lost", null)
