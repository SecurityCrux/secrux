package com.secrux.domain

import java.time.OffsetDateTime
import java.util.UUID

enum class BaselineKind { SAST, SCA, DAST, IAC }

data class Baseline(
    val baselineId: UUID,
    val tenantId: UUID,
    val projectId: UUID,
    val kind: BaselineKind,
    val fingerprints: List<String>,
    val generatedAt: OffsetDateTime
)

