package rikka.shizuku.server

import rikka.shizuku.server.util.Logger

abstract class ConfigManager {

    abstract fun find(uid: Int): ConfigPackageEntry?

    abstract fun update(uid: Int, packages: List<String>?, mask: Int, values: Int)

    abstract fun remove(uid: Int)

    companion object {

        protected val LOGGER = Logger("ConfigManager")

        const val FLAG_ALLOWED = 1 shl 1
        const val FLAG_DENIED = 1 shl 2
        const val MASK_PERMISSION = FLAG_ALLOWED or FLAG_DENIED
    }
}
