package com.secrux.dto

import com.secrux.domain.RepositoryGitAuthMode
import com.secrux.domain.RepositorySourceMode
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotNull
import java.util.UUID

@Schema(name = "RepositoryUpsertRequest")
data class RepositoryUpsertRequest(
    @field:NotNull val projectId: UUID = UUID(0, 0),
    @field:NotNull val sourceMode: RepositorySourceMode = RepositorySourceMode.REMOTE,
    val remoteUrl: String? = null,
    val scmType: String? = null,
    val defaultBranch: String? = null,
    val uploadKey: String? = null,
    val uploadChecksum: String? = null,
    val uploadSize: Long? = null,
    val secretRef: String? = null,
    val gitAuth: RepositoryGitAuthPayload? = null
)

@Schema(name = "RepositoryUpdateRequest")
data class RepositoryUpdateRequest(
    @field:NotNull val sourceMode: RepositorySourceMode = RepositorySourceMode.REMOTE,
    val remoteUrl: String? = null,
    val scmType: String? = null,
    val defaultBranch: String? = null,
    val uploadKey: String? = null,
    val uploadChecksum: String? = null,
    val uploadSize: Long? = null,
    val secretRef: String? = null,
    val gitAuth: RepositoryGitAuthPayload? = null
)

data class RepositoryGitAuthPayload(
    @field:NotNull val mode: RepositoryGitAuthMode = RepositoryGitAuthMode.NONE,
    val username: String? = null,
    val password: String? = null,
    val token: String? = null
)

@Schema(name = "RepositoryResponse")
data class RepositoryResponse(
    val repoId: UUID,
    val projectId: UUID,
    val tenantId: UUID,
    val sourceMode: RepositorySourceMode,
    val remoteUrl: String?,
    val scmType: String?,
    val defaultBranch: String?,
    val uploadKey: String?,
    val uploadChecksum: String?,
    val uploadSize: Long?,
    val secretRef: String?,
    val gitAuthMode: RepositoryGitAuthMode,
    val gitAuthConfigured: Boolean,
    val createdAt: String,
    val updatedAt: String?
)

@Schema(name = "RepositoryGitMetadataResponse")
data class RepositoryGitMetadataResponse(
    val repoId: UUID,
    val defaultBranch: String?,
    val headCommit: GitCommitSummary?,
    val branches: List<GitRefSummary>,
    val tags: List<GitRefSummary>,
    val commits: List<GitCommitSummary>,
    val fetchedAt: String
)

data class GitRefSummary(
    val name: String,
    val commitId: String
)

data class GitCommitSummary(
    val commitId: String,
    val shortMessage: String?,
    val authorName: String?,
    val authoredAt: String?,
    val ref: String?
)

