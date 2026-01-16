package com.secrux.repo

import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.util.UUID

data class Artifact(
    val artifactId: UUID,
    val tenantId: UUID,
    val taskId: UUID,
    val stageId: UUID,
    val uri: String,
    val kind: String,
    val checksum: String?,
    val sizeBytes: Long?
)

@Repository
class ArtifactRepository(
    private val dsl: DSLContext
) {

    private val table = DSL.table("artifact")
    private val tenantField = DSL.field("tenant_id", UUID::class.java)
    private val taskField = DSL.field("task_id", UUID::class.java)
    private val stageField = DSL.field("stage_id", UUID::class.java)
    private val artifactIdField = DSL.field("artifact_id", UUID::class.java)
    private val uriField = DSL.field("uri", String::class.java)
    private val kindField = DSL.field("kind", String::class.java)
    private val checksumField = DSL.field("checksum", String::class.java)
    private val sizeField = DSL.field("size_bytes", Long::class.javaObjectType)

    fun listByTask(taskId: UUID, tenantId: UUID): List<Artifact> {
        return dsl.selectFrom(table)
            .where(taskField.eq(taskId))
            .and(tenantField.eq(tenantId))
            .fetch {
                Artifact(
                    artifactId = it.get(artifactIdField),
                    tenantId = it.get(tenantField),
                    taskId = it.get(taskField),
                    stageId = it.get(stageField),
                    uri = it.get(uriField),
                    kind = it.get(kindField),
                    checksum = it.get(checksumField),
                    sizeBytes = it.get(sizeField)
                )
            }
    }
}

