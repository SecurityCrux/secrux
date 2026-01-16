package com.secrux.repo

import com.fasterxml.jackson.databind.ObjectMapper
import com.secrux.domain.RepositoryGitMetadata
import com.secrux.domain.RepositoryGitMetadataPayload
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.Record
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID

@Repository
class RepositoryGitMetadataRepository(
    private val dsl: DSLContext,
    private val objectMapper: ObjectMapper
) {

    private val table = DSL.table("repository_git_metadata")
    private val repoIdField = DSL.field("repo_id", UUID::class.java)
    private val tenantIdField = DSL.field("tenant_id", UUID::class.java)
    private val projectIdField = DSL.field("project_id", UUID::class.java)
    private val payloadField = DSL.field("payload", JSONB::class.java)
    private val fetchedAtField = DSL.field("fetched_at", OffsetDateTime::class.java)
    private val updatedAtField = DSL.field("updated_at", OffsetDateTime::class.java)

    fun find(repoId: UUID, tenantId: UUID): RepositoryGitMetadata? {
        return dsl.selectFrom(table)
            .where(repoIdField.eq(repoId))
            .and(tenantIdField.eq(tenantId))
            .fetchOne { mapRecord(it) }
    }

    fun upsert(metadata: RepositoryGitMetadata) {
        val payloadJson = objectMapper.toJsonb(metadata.payload)
        dsl.insertInto(table)
            .set(repoIdField, metadata.repoId)
            .set(tenantIdField, metadata.tenantId)
            .set(projectIdField, metadata.projectId)
            .set(payloadField, payloadJson)
            .set(fetchedAtField, metadata.fetchedAt)
            .set(updatedAtField, metadata.updatedAt)
            .onConflict(repoIdField)
            .doUpdate()
            .set(tenantIdField, metadata.tenantId)
            .set(projectIdField, metadata.projectId)
            .set(payloadField, payloadJson)
            .set(fetchedAtField, metadata.fetchedAt)
            .set(updatedAtField, metadata.updatedAt)
            .execute()
    }

    private fun mapRecord(record: Record): RepositoryGitMetadata {
        val payloadJson = record.get(payloadField)
        val payload = objectMapper.readValue(payloadJson.data(), RepositoryGitMetadataPayload::class.java)
        return RepositoryGitMetadata(
            repoId = record.get(repoIdField),
            tenantId = record.get(tenantIdField),
            projectId = record.get(projectIdField),
            payload = payload,
            fetchedAt = record.get(fetchedAtField),
            updatedAt = record.get(updatedAtField)
        )
    }
}
