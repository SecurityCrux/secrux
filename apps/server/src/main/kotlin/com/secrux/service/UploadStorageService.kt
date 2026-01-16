package com.secrux.service

import com.secrux.common.ErrorCode
import com.secrux.common.SecruxException
import com.secrux.config.UploadProperties
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

data class UploadHandle(
    val uploadId: String,
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,
    val path: Path
)

@Service
class UploadStorageService(
    private val props: UploadProperties
) {

    fun save(tenantId: UUID, file: MultipartFile): UploadHandle {
        if (file.isEmpty) {
            throw SecruxException(ErrorCode.VALIDATION_ERROR, "Upload file is empty")
        }
        val uploadId = UUID.randomUUID().toString()
        val safeName = sanitizeFileName(file.originalFilename ?: "upload.bin")
        val dir = tenantDir(tenantId).resolve(uploadId)
        Files.createDirectories(dir)
        val target = dir.resolve(safeName)
        val sha256 = computeSha256(file.inputStream) { input ->
            Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING)
        }
        val size = Files.size(target)
        return UploadHandle(
            uploadId = uploadId,
            fileName = safeName,
            sizeBytes = size,
            sha256 = sha256,
            path = target
        )
    }

    fun resolve(tenantId: UUID, uploadId: String): Path {
        val safeId = uploadId.trim()
        if (safeId.isBlank()) {
            throw SecruxException(ErrorCode.VALIDATION_ERROR, "uploadId cannot be blank")
        }
        val dir = tenantDir(tenantId).resolve(safeId).normalize()
        val root = tenantDir(tenantId).normalize()
        if (!dir.startsWith(root)) {
            throw SecruxException(ErrorCode.VALIDATION_ERROR, "Invalid uploadId")
        }
        if (!Files.exists(dir) || !Files.isDirectory(dir)) {
            throw SecruxException(ErrorCode.UPLOAD_NOT_FOUND, "Upload $uploadId not found")
        }
        Files.list(dir).use { stream ->
            val file = stream.filter(Files::isRegularFile).findFirst().orElse(null)
            if (file != null) return file
        }
        throw SecruxException(ErrorCode.UPLOAD_NOT_FOUND, "Upload $uploadId not found")
    }

    private fun tenantDir(tenantId: UUID): Path =
        Path.of(props.root).resolve(tenantId.toString())

    private fun sanitizeFileName(raw: String): String =
        raw
            .trim()
            .replace('\\', '_')
            .replace('/', '_')
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .takeIf { it.isNotBlank() }
            ?: "upload.bin"

    private fun computeSha256(inputStream: InputStream, writer: (InputStream) -> Unit): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val tee = DigestInputStream(inputStream, digest)
        tee.use { writer(it) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private class DigestInputStream(
        private val delegate: InputStream,
        private val digest: MessageDigest
    ) : InputStream() {

        override fun read(): Int {
            val value = delegate.read()
            if (value >= 0) {
                digest.update(value.toByte())
            }
            return value
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val count = delegate.read(b, off, len)
            if (count > 0) {
                digest.update(b, off, count)
            }
            return count
        }

        override fun close() {
            delegate.close()
        }
    }
}
