package com.secrux.common

class SecruxException(
    val errorCode: ErrorCode,
    override val message: String,
    val retryable: Boolean = false,
    val context: Map<String, Any?> = emptyMap(),
    cause: Throwable? = null
) : RuntimeException(message, cause)
