package eu.darken.porter.core

import android.os.Bundle

/**
 * Makes [args] resolve classes through the framework's loader only, before anything is read from it.
 * Up to API 32 the first read unparcels every value in a received Bundle, `Serializable`s included,
 * so a caller no check has admitted yet could otherwise name classes from the server's own classpath.
 */
fun confineToFramework(args: Bundle): Bundle = args.apply { classLoader = Bundle::class.java.classLoader }
