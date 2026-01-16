package com.secrux.service

import com.secrux.common.ErrorCode
import com.secrux.common.SecruxException
import com.secrux.domain.GitCommitInfo
import com.secrux.domain.GitRefInfo
import com.secrux.domain.Repository
import com.secrux.domain.RepositoryGitMetadata
import com.secrux.domain.RepositoryGitMetadataPayload
import com.secrux.dto.GitCommitSummary
import com.secrux.dto.GitRefSummary
import com.secrux.dto.RepositoryGitMetadataResponse
import com.secrux.repo.ProjectRepository
import com.secrux.repo.RepositoryGitMetadataRepository
import com.secrux.repo.RepositoryRepository
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ListBranchCommand
import org.eclipse.jgit.revwalk.RevCommit
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.io.path.deleteExisting
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries

@Service
class RepositoryGitService(
    private val projectRepository: ProjectRepository,
    private val repositoryRepository: RepositoryRepository,
    private val metadataRepository: RepositoryGitMetadataRepository,
    private val clock: Clock
) {

    fun getMetadata(
        tenantId: UUID,
        projectId: UUID,
        repoId: UUID,
        refresh: Boolean
    ): RepositoryGitMetadataResponse {
        val repository = loadRepository(tenantId, projectId, repoId)
        val metadata = if (refresh) {
            fetchAndPersist(repository)
        } else {
            metadataRepository.find(repoId, tenantId) ?: fetchAndPersist(repository)
        }
        return metadata.toResponse()
    }

    private fun fetchAndPersist(repository: Repository): RepositoryGitMetadata {
        val payload = fetchMetadata(repository)
        val now = OffsetDateTime.now(clock)
        val metadata = RepositoryGitMetadata(
            repoId = repository.repoId,
            tenantId = repository.tenantId,
            projectId = repository.projectId,
            payload = payload,
            fetchedAt = now,
            updatedAt = now
        )
        metadataRepository.upsert(metadata)
        return metadata
    }

    private fun fetchMetadata(repository: Repository): RepositoryGitMetadataPayload {
        val remoteUrl = repository.remoteUrl?.takeIf { it.isNotBlank() }
            ?: throw SecruxException(ErrorCode.VALIDATION_ERROR, "Repository remote URL is missing")

        val tempDir = Files.createTempDirectory("git-meta-")
        return try {
            Git.cloneRepository()
                .setURI(remoteUrl)
                .setDirectory(tempDir.toFile())
                .setBare(true)
                .setCloneAllBranches(true)
                .setDepth(50)
                .call().use { git ->
                    buildPayload(git, repository)
                }
        } catch (ex: Exception) {
            throw SecruxException(
                ErrorCode.INTERNAL_ERROR,
                "Failed to fetch git metadata: ${ex.message}"
            )
        } finally {
            deleteRecursively(tempDir)
        }
    }

    private fun buildPayload(git: Git, repository: Repository): RepositoryGitMetadataPayload {
        val branches = git.branchList()
            .setListMode(ListBranchCommand.ListMode.ALL)
            .call()
            .mapNotNull { ref ->
                val name = ref.name.removePrefix("refs/heads/").removePrefix("refs/remotes/origin/")
                if (name.isBlank()) null else GitRefInfo(name = name, commitId = ref.objectId?.name ?: "")
            }
            .distinctBy { it.name }

        val tags = git.tagList()
            .call()
            .mapNotNull { ref ->
                val name = ref.name.removePrefix("refs/tags/")
                if (name.isBlank()) null else GitRefInfo(name = name, commitId = ref.objectId?.name ?: "")
            }

        val configuredDefault = repository.defaultBranch?.takeIf { it.isNotBlank() }
        val detectedDefault = git.repository.fullBranch?.removePrefix("refs/heads/")
        val defaultBranch = configuredDefault ?: detectedDefault ?: branches.firstOrNull()?.name

        val commits = resolveCommits(git, defaultBranch)
        val headCommit = commits.firstOrNull()

        return RepositoryGitMetadataPayload(
            defaultBranch = defaultBranch,
            headCommit = headCommit,
            branches = branches.sortedBy { it.name },
            tags = tags.sortedBy { it.name },
            commits = commits
        )
    }

    private fun resolveCommits(git: Git, branch: String?): List<GitCommitInfo> {
        if (branch.isNullOrBlank()) {
            return emptyList()
        }
        return try {
            val branchRef = git.repository.findRef("refs/heads/$branch")
                ?: git.repository.findRef("refs/remotes/origin/$branch")
                ?: git.repository.findRef(branch)
            if (branchRef == null) {
                emptyList()
            } else {
                git.log()
                    .add(branchRef.objectId)
                    .setMaxCount(50)
                    .call()
                    .map { it.toCommitInfo(branch) }
            }
        } catch (ex: Exception) {
            emptyList()
        }
    }

    private fun RevCommit.toCommitInfo(branch: String?): GitCommitInfo {
        val authoredAt = authorIdent?.`when`?.toInstant()?.atOffset(ZoneOffset.UTC)
        return GitCommitInfo(
            commitId = name,
            shortMessage = shortMessage,
            authorName = authorIdent?.name,
            authoredAt = authoredAt,
            ref = branch
        )
    }

    private fun loadRepository(tenantId: UUID, projectId: UUID, repoId: UUID): Repository {
        projectRepository.findById(projectId, tenantId)
            ?: throw SecruxException(ErrorCode.PROJECT_NOT_FOUND, "Project not found")
        val repository = repositoryRepository.findById(repoId, tenantId)
            ?: throw SecruxException(ErrorCode.REPOSITORY_NOT_FOUND, "Repository not found")
        if (repository.projectId != projectId) {
            throw SecruxException(ErrorCode.FORBIDDEN, "Repository does not belong to project")
        }
        return repository
    }

    private fun deleteRecursively(path: Path) {
        if (!path.exists()) return
        if (path.isDirectory()) {
            path.listDirectoryEntries().forEach { deleteRecursively(it) }
        }
        try {
            path.deleteExisting()
        } catch (_: Exception) {
            // best-effort
        }
    }

    private fun RepositoryGitMetadata.toResponse(): RepositoryGitMetadataResponse =
        RepositoryGitMetadataResponse(
            repoId = repoId,
            defaultBranch = payload.defaultBranch,
            headCommit = payload.headCommit?.toSummary(),
            branches = payload.branches.map { GitRefSummary(name = it.name, commitId = it.commitId) },
            tags = payload.tags.map { GitRefSummary(name = it.name, commitId = it.commitId) },
            commits = payload.commits.map { it.toSummary() },
            fetchedAt = fetchedAt.toString()
        )

    private fun GitCommitInfo.toSummary(): GitCommitSummary =
        GitCommitSummary(
            commitId = commitId,
            shortMessage = shortMessage,
            authorName = authorName,
            authoredAt = authoredAt?.toString(),
            ref = ref
        )
}
