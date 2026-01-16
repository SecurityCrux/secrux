package com.secrux.dto

data class PageResponse<T>(
    val items: List<T>,
    val total: Long,
    val limit: Int,
    val offset: Int
)

