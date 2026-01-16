package com.secrux.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank

@Schema(name = "ScanExecRequest")
data class ScanExecRequest(
    @field:NotBlank val engine: String = "semgrep",
    val paths: List<String> = emptyList()
)

@Schema(name = "ResultProcessRequest")
data class ResultProcessRequest(
    val sarifLocations: List<String> = emptyList()
)

@Schema(name = "ResultReviewRequest")
data class ResultReviewRequest(
    val autoTicket: Boolean = true,
    val ticketProvider: String = "stub",
    val labels: List<String> = emptyList(),
    val aiReviewEnabled: Boolean? = null,
    val aiReviewMode: String? = null,
    val aiReviewDataFlowMode: String? = null
)

