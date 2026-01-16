package com.secrux.service

import com.secrux.common.ErrorCode
import com.secrux.common.SecruxException
import org.springframework.stereotype.Component

@Component
class ScaEngineRegistry(
    engines: List<ScaEngine>
) {
    private val engineMap: Map<String, ScaEngine> = engines.associateBy { it.id.lowercase() }

    fun resolve(requested: String?): ScaEngine {
        val key = requested?.trim()?.lowercase().takeIf { !it.isNullOrBlank() } ?: "trivy"
        return engineMap[key] ?: throw SecruxException(ErrorCode.VALIDATION_ERROR, "Unsupported SCA engine: $key")
    }
}

