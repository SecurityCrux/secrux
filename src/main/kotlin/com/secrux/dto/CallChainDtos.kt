package com.secrux.dto

data class CallChainStepDto(
    val nodeId: String,
    val role: String? = null,
    val label: String,
    val file: String? = null,
    val line: Int? = null,
    val startColumn: Int? = null,
    val endColumn: Int? = null,
    val snippet: String? = null,
)

data class CallChainDto(
    val chainId: String,
    val steps: List<CallChainStepDto>,
)

