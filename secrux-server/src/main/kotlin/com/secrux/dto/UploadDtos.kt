package com.secrux.dto

import io.swagger.v3.oas.annotations.media.Schema

@Schema(name = "UploadResponse")
data class UploadResponse(
    val uploadId: String,
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String
)

