package com.securitycrux.secrux.intellij.sinks

class SinkCatalogLoadException(
    val messageKey: String,
    val messageArgs: Array<out Any> = emptyArray(),
    cause: Throwable? = null
) : RuntimeException(messageKey, cause)
