package com.secrux.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.secrux.common.ErrorCode
import com.secrux.common.SecruxException
import com.secrux.domain.Project
import com.secrux.domain.Repository
import com.secrux.domain.RepositoryGitAuth
import com.secrux.domain.RepositoryGitAuthMode
import com.secrux.domain.RepositorySourceMode
import com.secrux.dto.ProjectResponse
import com.secrux.dto.ProjectUpsertRequest
import com.secrux.dto.RepositoryGitAuthPayload
import com.secrux.dto.RepositoryResponse
import com.secrux.dto.RepositoryUpdateRequest
import com.secrux.dto.RepositoryUpsertRequest
import com.secrux.repo.ProjectRepository
import com.secrux.repo.RepositoryRepository
import com.secrux.security.SecretCrypto
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID

@Service
class ProjectService(
    private val projectRepository: ProjectRepository,
    private val repositoryRepository: RepositoryRepository,
    private val secretCrypto: SecretCrypto,
    private val objectMapper: ObjectMapper,
    private val clock: Clock
) {

    fun createProject(tenantId: UUID, request: ProjectUpsertRequest): ProjectResponse {
        val now = OffsetDateTime.now(clock)
        val project = Project(
            projectId = UUID.randomUUID(),
            tenantId = tenantId,
            name = request.name,
            codeOwners = request.codeOwners.distinct(),
            createdAt = now,
            updatedAt = now
        )
        projectRepository.insert(project)
        return project.toResponse()
    }

    fun updateProject(tenantId: UUID, projectId: UUID, request: ProjectUpsertRequest): ProjectResponse {
        val existing = projectRepository.findById(projectId, tenantId)
            ?: throw SecruxException(ErrorCode.PROJECT_NOT_FOUND, "Project not found")
        val updated = existing.copy(
            name = request.name,
            codeOwners = request.codeOwners.distinct(),
            updatedAt = OffsetDateTime.now(clock)
        )
        projectRepository.update(updated)
        return updated.toResponse()
    }

    fun listProjects(tenantId: UUID): List<ProjectResponse> =
        projectRepository.list(tenantId).map { it.toResponse() }

    fun getProject(tenantId: UUID, projectId: UUID): ProjectResponse {
        return projectRepository.findById(projectId, tenantId)?.toResponse()
            ?: throw SecruxException(ErrorCode.PROJECT_NOT_FOUND, "Project not found")
    }

    fun createRepository(tenantId: UUID, request: RepositoryUpsertRequest): RepositoryResponse {
        projectRepository.findById(request.projectId, tenantId)
            ?: throw SecruxException(ErrorCode.PROJECT_NOT_FOUND, "Project not found for repository")
        val now = OffsetDateTime.now(clock)
        val repo = Repository(
            repoId = UUID.randomUUID(),
            tenantId = tenantId,
            projectId = request.projectId,
            sourceMode = request.sourceMode,
            remoteUrl = request.remoteUrl,
            scmType = request.scmType,
            defaultBranch = request.defaultBranch,
            uploadKey = request.uploadKey,
            uploadChecksum = request.uploadChecksum,
            uploadSize = request.uploadSize,
            secretRef = request.secretRef,
            gitAuth = encodeGitAuth(request.gitAuth),
            createdAt = now,
            updatedAt = now
        )
        repositoryRepository.insert(repo)
        return repo.toResponse()
    }

    fun listRepositories(tenantId: UUID, projectId: UUID): List<RepositoryResponse> {
        projectRepository.findById(projectId, tenantId)
            ?: throw SecruxException(ErrorCode.PROJECT_NOT_FOUND, "Project not found")
        return repositoryRepository.listByProject(projectId, tenantId).map { it.toResponse() }
    }

    fun updateRepository(
        tenantId: UUID,
        projectId: UUID,
        repoId: UUID,
        request: RepositoryUpdateRequest
    ): RepositoryResponse {
        projectRepository.findById(projectId, tenantId)
            ?: throw SecruxException(ErrorCode.PROJECT_NOT_FOUND, "Project not found")
        val existing = repositoryRepository.findById(repoId, tenantId)
            ?: throw SecruxException(ErrorCode.REPOSITORY_NOT_FOUND, "Repository not found")
        if (existing.projectId != projectId) {
            throw SecruxException(ErrorCode.FORBIDDEN, "Repository does not belong to project")
        }
        val updated = existing.copy(
            sourceMode = request.sourceMode,
            remoteUrl = request.remoteUrl,
            scmType = request.scmType,
            defaultBranch = request.defaultBranch,
            uploadKey = request.uploadKey,
            uploadChecksum = request.uploadChecksum,
            uploadSize = request.uploadSize,
            secretRef = request.secretRef,
            gitAuth = request.gitAuth?.let { encodeGitAuth(it) } ?: existing.gitAuth,
            updatedAt = OffsetDateTime.now(clock)
        )
        repositoryRepository.update(updated)
        return updated.toResponse()
    }

    fun deleteProject(tenantId: UUID, projectId: UUID) {
        projectRepository.findById(projectId, tenantId)
            ?: throw SecruxException(ErrorCode.PROJECT_NOT_FOUND, "Project not found")
        val now = OffsetDateTime.now(clock)
        projectRepository.softDelete(projectId, tenantId, now)
        repositoryRepository.softDeleteByProject(projectId, tenantId, now)
    }

    fun deleteRepository(tenantId: UUID, projectId: UUID, repoId: UUID) {
        projectRepository.findById(projectId, tenantId)
            ?: throw SecruxException(ErrorCode.PROJECT_NOT_FOUND, "Project not found")
        val repo = repositoryRepository.findById(repoId, tenantId)
            ?: throw SecruxException(ErrorCode.REPOSITORY_NOT_FOUND, "Repository not found")
        if (repo.projectId != projectId) {
            throw SecruxException(ErrorCode.FORBIDDEN, "Repository does not belong to project")
        }
        repositoryRepository.softDelete(repoId, tenantId, OffsetDateTime.now(clock))
    }

    private fun encodeGitAuth(payload: RepositoryGitAuthPayload?): RepositoryGitAuth {
        val mode = payload?.mode ?: RepositoryGitAuthMode.NONE
        if (mode == RepositoryGitAuthMode.NONE) {
            return RepositoryGitAuth(mode = RepositoryGitAuthMode.NONE, credentialCipher = null)
        }
        val secretPayload = when (mode) {
            RepositoryGitAuthMode.BASIC -> {
                val username =
                    payload?.username?.takeIf { it.isNotBlank() }
                        ?: throw SecruxException(ErrorCode.VALIDATION_ERROR, "Git username is required for basic auth")
                val password =
                    payload.password?.takeIf { it.isNotBlank() }
                        ?: throw SecruxException(ErrorCode.VALIDATION_ERROR, "Git password is required for basic auth")
                mapOf("username" to username, "password" to password)
            }

            RepositoryGitAuthMode.TOKEN -> {
                val token =
                    payload?.token?.takeIf { it.isNotBlank() }
                        ?: throw SecruxException(ErrorCode.VALIDATION_ERROR, "Access token is required")
                val username = payload.username?.takeIf { it.isNotBlank() }
                val secret = mutableMapOf("token" to token)
                username?.let { secret["username"] = it }
                secret
            }

            RepositoryGitAuthMode.NONE -> emptyMap()
        }
        val cipher =
            secretCrypto.encrypt(objectMapper.writeValueAsString(secretPayload))
        return RepositoryGitAuth(mode = mode, credentialCipher = cipher)
    }
}
