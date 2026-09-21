package rikka.shizuku.server.util

import android.os.Build

object AbiUtil {

    private val has32Bit: Boolean by lazy { Build.SUPPORTED_32_BIT_ABIS.isNotEmpty() }

    fun has32Bit(): Boolean = has32Bit
}
