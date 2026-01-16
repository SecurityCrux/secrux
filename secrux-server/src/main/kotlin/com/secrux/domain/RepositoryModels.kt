package com.secrux.domain

import java.time.OffsetDateTime
import java.util.UUID

enum class RepositorySourceMode { REMOTE, UPLOAD, MIXED }
enum class RepositoryGitAuthMode { NONE, BASIC, TOKEN }

data class RepositoryGitAuth(
    val mode: RepositoryGitAuthMode = RepositoryGitAuthMode.NONE,
    val credentialCipher: String? = null
)

data class Repository(
    val repoId: UUID,
    val tenantId: UUID,
    val projectId: UUID,
    val sourceMode: RepositorySourceMode,
    val remoteUrl: String?,
    val scmType: String?,
    val defaultBranch: String?,
    val uploadKey: String?,
    val uploadChecksum: String?,
    val uploadSize: Long?,
    val secretRef: String?,
    val gitAuth: RepositoryGitAuth,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime?,
    val deletedAt: OffsetDateTime? = null
)

data class RepositoryGitMetadata(
    val repoId: UUID,
    val tenantId: UUID,
    val projectId: UUID,
    val payload: RepositoryGitMetadataPayload,
    val fetchedAt: OffsetDateTime,
    val updatedAt: OffsetDateTime?
)

data class RepositoryGitMetadataPayload(
    val defaultBranch: String? = null,
    val headCommit: GitCommitInfo? = null,
    val branches: List<GitRefInfo> = emptyList(),
    val tags: List<GitRefInfo> = emptyList(),
    val commits: List<GitCommitInfo> = emptyList()
)

data class GitRefInfo(
    val name: String = "",
    val commitId: String = ""
)

data class GitCommitInfo(
    val commitId: String = "",
    val shortMessage: String? = null,
    val authorName: String? = null,
    val authoredAt: OffsetDateTime? = null,
    val ref: String? = null
)

