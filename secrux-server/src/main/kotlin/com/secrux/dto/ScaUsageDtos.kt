package com.secrux.dto

import io.swagger.v3.oas.annotations.media.Schema

@Schema(name = "ScaUsageEntry")
data class ScaUsageEntryDto(
    val ecosystem: String? = null,
    val key: String? = null,
    val file: String? = null,
    val line: Int? = null,
    val kind: String? = null,
    val snippet: String? = null,
    val language: String? = null,
    val symbol: String? = null,
    val receiver: String? = null,
    val callee: String? = null,
    val startLine: Int? = null,
    val startCol: Int? = null,
    val endLine: Int? = null,
    val endCol: Int? = null,
    val confidence: Double? = null,
)
