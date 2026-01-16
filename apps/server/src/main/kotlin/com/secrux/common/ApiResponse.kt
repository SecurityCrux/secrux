package com.secrux.common

data class ApiResponse<T>(
    val success: Boolean = true,
    val code: String = ErrorCode.SUCCESS.name,
    val message: String? = null,
    val data: T? = null
)
