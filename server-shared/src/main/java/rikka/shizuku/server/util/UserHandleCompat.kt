package rikka.shizuku.server.util

object UserHandleCompat {

    const val PER_USER_RANGE = 100000

    fun getUserId(uid: Int): Int = uid / PER_USER_RANGE

    fun getAppId(uid: Int): Int = uid % PER_USER_RANGE
}
