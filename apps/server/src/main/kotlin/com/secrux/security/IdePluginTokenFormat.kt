package com.secrux.security

object IdePluginTokenFormat {
    const val PREFIX = "sxp_"

    fun isIdePluginToken(token: String): Boolean =
        token.startsWith(PREFIX)
}

