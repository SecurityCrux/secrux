package com.secrux.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.secrux.common.ErrorCode
import com.secrux.common.SecruxException
import com.secrux.domain.Task
import com.secrux.repo.RepositoryRepository
import com.secrux.security.SecretCrypto
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class WorkspaceHandle(val root: Path)

@Component
class WorkspaceService(
    @Value("\${secrux.workspace.root:build/workspaces}") private val rootDir: String,
    private val repositoryRepository: RepositoryRepository,
    private val secretCrypto: SecretCrypto,
    private val objectMapper: ObjectMapper,
    private val uploadStorageService: UploadStorageService
) {

    private val cache = ConcurrentHashMap<UUID, Path>()
    private val workspaceRoot: Path = Path.of(rootDir)
    private val targetWriter = WorkspaceTargetWriter()
    private val zipExtractor = ZipExtractor()
    private val archiveWorkspacePreparer = ArchiveWorkspacePreparer(uploadStorageService, zipExtractor)
    private val filesystemWorkspacePreparer = FilesystemWorkspacePreparer(archiveWorkspacePreparer, targetWriter)
    private val imageWorkspacePreparer = ImageWorkspacePreparer(targetWriter)
    private val sbomWorkspacePreparer = SbomWorkspacePreparer(uploadStorageService, targetWriter)
    private val gitWorkspacePreparer =
        GitWorkspacePreparer(
            GitCredentialResolver(repositoryRepository, secretCrypto, objectMapper)
        )

    fun prepare(task: Task): WorkspaceHandle {
        val source = task.spec.source
        val workspace = workspaceRoot.resolve(task.taskId.toString())
        recreateDirectory(workspace)
        when {
            source.git != null -> gitWorkspacePreparer.prepare(task, source.git, workspace)
            source.archive != null -> archiveWorkspacePreparer.prepare(task.tenantId, source.archive, workspace)
            source.filesystem != null ->
                filesystemWorkspacePreparer.prepare(task.tenantId, source.filesystem.path, source.filesystem.uploadId, workspace)
            source.image != null -> imageWorkspacePreparer.prepare(source.image, workspace)
            source.sbom != null -> sbomWorkspacePreparer.prepare(task.tenantId, source.sbom, workspace)
            else -> throw SecruxException(ErrorCode.VALIDATION_ERROR, "Unsupported source type for workspace")
        }
        cache[task.taskId] = workspace
        return WorkspaceHandle(workspace)
    }

    fun resolve(taskId: UUID): Path {
        cache[taskId]?.let { return it }

        val workspace = workspaceRoot.resolve(taskId.toString())
        if (Files.exists(workspace) && Files.isDirectory(workspace)) {
            cache[taskId] = workspace
            return workspace
        }

        throw SecruxException(ErrorCode.VALIDATION_ERROR, "Workspace not prepared for task $taskId")
    }

    private fun recreateDirectory(path: Path) {
        if (Files.exists(path)) {
            Files.walk(path)
                .sorted(Comparator.reverseOrder())
                .forEach { Files.deleteIfExists(it) }
        }
        Files.createDirectories(path)
    }
}
