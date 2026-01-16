package com.secrux.service

import com.secrux.domain.Project
import com.secrux.domain.Repository
import com.secrux.dto.ProjectResponse
import com.secrux.dto.RepositoryResponse

internal fun Project.toResponse(): ProjectResponse =
    ProjectResponse(
        projectId = projectId,
        tenantId = tenantId,
        name = name,
        codeOwners = codeOwners,
        createdAt = createdAt.toString(),
        updatedAt = updatedAt?.toString()
    )

internal fun Repository.toResponse(): RepositoryResponse =
    RepositoryResponse(
        repoId = repoId,
        projectId = projectId,
        tenantId = tenantId,
        sourceMode = sourceMode,
        remoteUrl = remoteUrl,
        scmType = scmType,
        defaultBranch = defaultBranch,
        uploadKey = uploadKey,
        uploadChecksum = uploadChecksum,
        uploadSize = uploadSize,
        secretRef = secretRef,
        gitAuthMode = gitAuth.mode,
        gitAuthConfigured = !gitAuth.credentialCipher.isNullOrBlank(),
        createdAt = createdAt.toString(),
        updatedAt = updatedAt?.toString()
    )

