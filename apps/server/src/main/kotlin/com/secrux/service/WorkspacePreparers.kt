package com.secrux.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.secrux.common.ErrorCode
import com.secrux.common.SecruxException
import com.secrux.domain.ArchiveSourceSpec
import com.secrux.domain.GitSourceSpec
import com.secrux.domain.ImageSourceSpec
import com.secrux.domain.RepositoryGitAuthMode
import com.secrux.domain.SbomSourceSpec
import com.secrux.domain.SourceRefType
import com.secrux.domain.Task
import com.secrux.repo.RepositoryRepository
import com.secrux.security.SecretCrypto
import org.eclipse.jgit.api.CreateBranchCommand
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlin.io.path.isDirectory

internal class WorkspaceTargetWriter {
    fun write(workspace: Path, value: String) {
        Files.writeString(workspace.resolve(".secrux_target").normalize(), value)
    }
}

internal class ZipExtractor {
    fun extract(zipFile: Path, workspace: Path) {
        ZipInputStream(Files.newInputStream(zipFile)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val entryPath = workspace.resolve(entry.name).normalize()
                if (!entryPath.startsWith(workspace)) {
                    throw SecruxException(ErrorCode.VALIDATION_ERROR, "Archive entry escapes workspace: ${entry.name}")
                }
                if (entry.isDirectory) {
                    Files.createDirectories(entryPath)
                } else {
                    Files.createDirectories(entryPath.parent)
                    Files.copy(zip, entryPath, StandardCopyOption.REPLACE_EXISTING)
                }
                zip.closeEntry()
            }
        }
    }
}

internal class ArchiveWorkspacePreparer(
    private val uploadStorageService: UploadStorageService,
    private val zipExtractor: ZipExtractor
) {
    fun prepare(tenantId: UUID, archive: ArchiveSourceSpec, workspace: Path) {
        val uploadId = archive.uploadId?.takeIf { it.isNotBlank() }
        val source =
            if (uploadId != null) {
                uploadStorageService.resolve(tenantId, uploadId)
            } else {
                val path = archive.url?.takeIf { it.isNotBlank() }
                    ?: throw SecruxException(ErrorCode.VALIDATION_ERROR, "archive.uploadId or archive.url is required")
                Path.of(path)
            }
        if (!Files.exists(source) || Files.isDirectory(source)) {
            throw SecruxException(ErrorCode.VALIDATION_ERROR, "Archive source not accessible: $source")
        }
        val fileName = source.fileName.toString().lowercase()
        if (!fileName.endsWith(".zip")) {
            throw SecruxException(ErrorCode.VALIDATION_ERROR, "Only .zip archives are supported for now")
        }
        zipExtractor.extract(source, workspace)
    }
}

internal class FilesystemWorkspacePreparer(
    private val archiveWorkspacePreparer: ArchiveWorkspacePreparer,
    private val targetWriter: WorkspaceTargetWriter
) {
    fun prepare(tenantId: UUID, path: String?, uploadId: String?, workspace: Path) {
        val cleanUploadId = uploadId?.takeIf { it.isNotBlank() }
        if (cleanUploadId != null) {
            archiveWorkspacePreparer.prepare(tenantId, ArchiveSourceSpec(uploadId = cleanUploadId), workspace)
            targetWriter.write(workspace, "filesystem:upload:$cleanUploadId")
            return
        }
        val fsPath = path?.takeIf { it.isNotBlank() }
            ?: throw SecruxException(ErrorCode.VALIDATION_ERROR, "filesystem.path or filesystem.uploadId is required")
        val resolved = Path.of(fsPath).normalize()
        if (!Files.exists(resolved)) {
            throw SecruxException(ErrorCode.VALIDATION_ERROR, "Filesystem path not accessible: $resolved")
        }
        targetWriter.write(workspace, "filesystem:path:${resolved.toAbsolutePath()}")
    }
}

internal class ImageWorkspacePreparer(
    private val targetWriter: WorkspaceTargetWriter
) {
    fun prepare(image: ImageSourceSpec, workspace: Path) {
        val ref = image.ref.trim().takeIf { it.isNotBlank() }
            ?: throw SecruxException(ErrorCode.VALIDATION_ERROR, "image.ref is required")
        targetWriter.write(workspace, "image:$ref")
    }
}

internal class SbomWorkspacePreparer(
    private val uploadStorageService: UploadStorageService,
    private val targetWriter: WorkspaceTargetWriter
) {
    fun prepare(tenantId: UUID, sbom: SbomSourceSpec, workspace: Path) {
        val uploadId = sbom.uploadId?.takeIf { it.isNotBlank() }
        val source =
            if (uploadId != null) {
                uploadStorageService.resolve(tenantId, uploadId)
            } else {
                val path = sbom.url?.takeIf { it.isNotBlank() }
                    ?: throw SecruxException(ErrorCode.VALIDATION_ERROR, "sbom.uploadId or sbom.url is required")
                Path.of(path)
            }
        if (!Files.exists(source) || Files.isDirectory(source)) {
            throw SecruxException(ErrorCode.VALIDATION_ERROR, "SBOM source not accessible: $source")
        }
        val sbomDir = workspace.resolve("__sbom__")
        Files.createDirectories(sbomDir)
        Files.copy(source, sbomDir.resolve("input.json"), StandardCopyOption.REPLACE_EXISTING)
        targetWriter.write(workspace, "sbom:${sbomDir.resolve("input.json").toAbsolutePath()}")
    }
}

internal class GitCredentialResolver(
    private val repositoryRepository: RepositoryRepository,
    private val secretCrypto: SecretCrypto,
    private val objectMapper: ObjectMapper
) {
    fun resolve(task: Task, git: GitSourceSpec): GitCredential? {
        extractInlineCredential(git)?.let { return it }
        val repoId = task.repoId ?: return null
        val repository =
            repositoryRepository.findById(repoId, task.tenantId)
                ?: throw SecruxException(
                    ErrorCode.REPOSITORY_NOT_FOUND,
                    "Repository $repoId not found for task ${task.taskId}"
                )
        val cipher = repository.gitAuth.credentialCipher ?: return null
        val payload = runCatching { secretCrypto.decrypt(cipher) }
            .getOrElse { throw SecruxException(ErrorCode.INTERNAL_ERROR, "Failed to decrypt repository credential") }
        val node = objectMapper.readTree(payload)
        return when (repository.gitAuth.mode) {
            RepositoryGitAuthMode.BASIC -> {
                val username = node.path("username").asText(null)
                val password = node.path("password").asText(null)
                if (username.isNullOrBlank() || password.isNullOrBlank()) null else GitCredential(username, password)
            }

            RepositoryGitAuthMode.TOKEN -> {
                val token = node.path("token").asText(null)
                if (token.isNullOrBlank()) {
                    null
                } else {
                    val username = node.path("username").asText(null).takeUnless { it.isNullOrBlank() } ?: "token"
                    GitCredential(username, token)
                }
            }

            RepositoryGitAuthMode.NONE -> null
        }
    }

    private fun extractInlineCredential(git: GitSourceSpec): GitCredential? {
        val auth = git.auth ?: return null
        val token = auth["token"]?.takeIf { it.isNotBlank() }
        if (token != null) {
            val username = auth["username"]?.takeIf { it.isNotBlank() } ?: "token"
            return GitCredential(username, token)
        }
        val username = auth["username"]?.takeIf { it.isNotBlank() }
        val password = auth["password"]?.takeIf { it.isNotBlank() }
        return if (!username.isNullOrBlank() && !password.isNullOrBlank()) {
            GitCredential(username, password)
        } else {
            null
        }
    }

    data class GitCredential(
        val username: String,
        val password: String
    )
}

internal class GitWorkspacePreparer(
    private val credentialResolver: GitCredentialResolver
) {
    fun prepare(task: Task, git: GitSourceSpec, workspace: Path) {
        if (isRemoteRepo(git.repo)) {
            cloneRemoteWorkspace(task, git, workspace)
        } else {
            copyLocalWorkspace(git, workspace)
        }
    }

    private fun cloneRemoteWorkspace(task: Task, git: GitSourceSpec, workspace: Path) {
        val credential = credentialResolver.resolve(task, git)
        try {
            Git.cloneRepository()
                .setURI(git.repo)
                .setDirectory(workspace.toFile())
                .setCloneAllBranches(git.refType == SourceRefType.COMMIT)
                .apply {
                    credential?.let { setCredentialsProvider(UsernamePasswordCredentialsProvider(it.username, it.password)) }
                }
                .call().use { cloned ->
                    checkoutReference(cloned, git)
                }
        } catch (ex: Exception) {
            throw SecruxException(
                ErrorCode.VALIDATION_ERROR,
                "Failed to clone repository ${git.repo}: ${ex.message}"
            )
        }
    }

    private fun copyLocalWorkspace(git: GitSourceSpec, workspace: Path) {
        val sourcePath = Path.of(git.repo)
        if (!Files.exists(sourcePath) || !sourcePath.isDirectory()) {
            throw SecruxException(ErrorCode.VALIDATION_ERROR, "Git repository ${git.repo} not accessible")
        }
        Files.walk(sourcePath).use { paths ->
            paths.forEach { path ->
                val relative = sourcePath.relativize(path)
                val target = workspace.resolve(relative)
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target)
                } else {
                    Files.createDirectories(target.parent)
                    Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }

    private fun checkoutReference(git: Git, spec: GitSourceSpec) {
        val ref = spec.ref.takeUnless { it.isBlank() } ?: return
        try {
            when (spec.refType) {
                SourceRefType.TAG -> {
                    git.checkout()
                        .setName("tag-$ref")
                        .setStartPoint("refs/tags/$ref")
                        .setCreateBranch(true)
                        .call()
                }

                SourceRefType.COMMIT -> {
                    git.checkout()
                        .setName("commit-$ref")
                        .setStartPoint(ref)
                        .setCreateBranch(true)
                        .call()
                }

                else -> {
                    git.checkout()
                        .setCreateBranch(true)
                        .setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.TRACK)
                        .setStartPoint("origin/$ref")
                        .setName(ref)
                        .call()
                }
            }
        } catch (_: Exception) {
            // leave HEAD as-is when checkout fails
        }
    }

    private fun isRemoteRepo(uri: String): Boolean =
        uri.startsWith("http://", ignoreCase = true) ||
            uri.startsWith("https://", ignoreCase = true) ||
            uri.startsWith("ssh://", ignoreCase = true) ||
            uri.startsWith("git@", ignoreCase = true)
}

