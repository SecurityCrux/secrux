package com.secrux.service

import com.secrux.domain.Severity

internal fun mapSarifSeverity(level: String?, propertiesSeverity: String? = null): Severity {
    val normalized = propertiesSeverity?.trim()?.takeIf { it.isNotBlank() } ?: level?.trim()
    return when (normalized?.lowercase()) {
        "critical" -> Severity.CRITICAL
        "high" -> Severity.HIGH
        "medium" -> Severity.MEDIUM
        "low" -> Severity.LOW
        "info", "information" -> Severity.INFO
        "error" -> Severity.HIGH
        "warning" -> Severity.MEDIUM
        "note" -> Severity.LOW
        "none" -> Severity.INFO
        else -> Severity.INFO
    }
}

