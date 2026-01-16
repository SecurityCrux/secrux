package com.secrux.repo

import com.secrux.domain.Repository
import com.secrux.domain.RepositoryGitAuth
import com.secrux.domain.RepositoryGitAuthMode
import com.secrux.domain.RepositorySourceMode
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository as SpringRepository
import java.time.OffsetDateTime
import java.util.UUID

@SpringRepository
class RepositoryRepository(
    private val dsl: DSLContext
) {

    private val table = DSL.table("repository")
    private val repoIdField = DSL.field("repo_id", UUID::class.java)
    private val projectIdField = DSL.field("project_id", UUID::class.java)
    private val tenantField = DSL.field("tenant_id", UUID::class.java)
    private val sourceModeField = DSL.field("source_mode", String::class.java)
    private val remoteUrlField = DSL.field("remote_url", String::class.java)
    private val scmTypeField = DSL.field("scm_type", String::class.java)
    private val defaultBranchField = DSL.field("default_branch", String::class.java)
    private val uploadKeyField = DSL.field("upload_key", String::class.java)
    private val uploadChecksumField = DSL.field("upload_checksum", String::class.java)
    private val uploadSizeField = DSL.field("upload_size", Long::class.javaObjectType)
    private val secretRefField = DSL.field("secret_ref", String::class.java)
    private val gitAuthModeField = DSL.field("git_auth_mode", String::class.java)
    private val gitAuthCipherField = DSL.field("git_auth_cipher", String::class.java)
    private val createdAtField = DSL.field("created_at", OffsetDateTime::class.java)
    private val updatedAtField = DSL.field("updated_at", OffsetDateTime::class.java)
    private val deletedAtField = DSL.field("deleted_at", OffsetDateTime::class.java)

    fun insert(repo: Repository) {
        dsl.insertInto(table)
            .set(repoIdField, repo.repoId)
            .set(projectIdField, repo.projectId)
            .set(tenantField, repo.tenantId)
            .set(sourceModeField, repo.sourceMode.name.lowercase())
            .set(remoteUrlField, repo.remoteUrl)
            .set(scmTypeField, repo.scmType)
            .set(defaultBranchField, repo.defaultBranch)
            .set(uploadKeyField, repo.uploadKey)
            .set(uploadChecksumField, repo.uploadChecksum)
            .set(uploadSizeField, repo.uploadSize)
            .set(secretRefField, repo.secretRef)
            .set(gitAuthModeField, repo.gitAuth.mode.name.lowercase())
            .set(gitAuthCipherField, repo.gitAuth.credentialCipher)
            .set(createdAtField, repo.createdAt)
            .set(updatedAtField, repo.updatedAt)
            .set(deletedAtField, repo.deletedAt)
            .execute()
    }

    fun findById(repoId: UUID, tenantId: UUID): Repository? {
        return dsl.selectFrom(table)
            .where(repoIdField.eq(repoId))
            .and(tenantField.eq(tenantId))
            .and(deletedAtField.isNull)
            .fetchOne { mapRepo(it) }
    }

    fun listByProject(projectId: UUID, tenantId: UUID): List<Repository> {
        return dsl.selectFrom(table)
            .where(projectIdField.eq(projectId))
            .and(tenantField.eq(tenantId))
            .and(deletedAtField.isNull)
            .orderBy(createdAtField.desc())
            .fetch { mapRepo(it) }
    }

    fun update(repo: Repository) {
        dsl.update(table)
            .set(sourceModeField, repo.sourceMode.name.lowercase())
            .set(remoteUrlField, repo.remoteUrl)
            .set(scmTypeField, repo.scmType)
            .set(defaultBranchField, repo.defaultBranch)
            .set(uploadKeyField, repo.uploadKey)
            .set(uploadChecksumField, repo.uploadChecksum)
            .set(uploadSizeField, repo.uploadSize)
            .set(secretRefField, repo.secretRef)
            .set(gitAuthModeField, repo.gitAuth.mode.name.lowercase())
            .set(gitAuthCipherField, repo.gitAuth.credentialCipher)
            .set(updatedAtField, repo.updatedAt)
            .where(repoIdField.eq(repo.repoId))
            .and(tenantField.eq(repo.tenantId))
            .and(deletedAtField.isNull)
            .execute()
    }

    fun softDelete(repoId: UUID, tenantId: UUID, deletedAt: OffsetDateTime): Int {
        return dsl.update(table)
            .set(deletedAtField, deletedAt)
            .where(repoIdField.eq(repoId))
            .and(tenantField.eq(tenantId))
            .and(deletedAtField.isNull)
            .execute()
    }

    fun softDeleteByProject(projectId: UUID, tenantId: UUID, deletedAt: OffsetDateTime) {
        dsl.update(table)
            .set(deletedAtField, deletedAt)
            .where(projectIdField.eq(projectId))
            .and(tenantField.eq(tenantId))
            .and(deletedAtField.isNull)
            .execute()
    }

    private fun mapRepo(record: Record): Repository {
        return com.secrux.domain.Repository(
            repoId = record.get(repoIdField),
            tenantId = record.get(tenantField),
            projectId = record.get(projectIdField),
            sourceMode = RepositorySourceMode.valueOf(record.get(sourceModeField).uppercase()),
            remoteUrl = record.get(remoteUrlField),
            scmType = record.get(scmTypeField),
            defaultBranch = record.get(defaultBranchField),
            uploadKey = record.get(uploadKeyField),
            uploadChecksum = record.get(uploadChecksumField),
            uploadSize = record.get(uploadSizeField),
            secretRef = record.get(secretRefField),
            gitAuth = RepositoryGitAuth(
                mode = record.get(gitAuthModeField)?.let { RepositoryGitAuthMode.valueOf(it.uppercase()) }
                    ?: RepositoryGitAuthMode.NONE,
                credentialCipher = record.get(gitAuthCipherField)
            ),
            createdAt = record.get(createdAtField),
            updatedAt = record.get(updatedAtField),
            deletedAt = record.get(deletedAtField)
        )
    }
}
