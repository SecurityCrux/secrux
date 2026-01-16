package com.securitycrux.secrux.intellij.util

import com.intellij.openapi.project.Project
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

data class GitRemoteInfo(
    val rawUrl: String,
    val normalizedUrl: String,
)

object GitRemoteResolver {

    fun resolvePrimaryRemote(project: Project): GitRemoteInfo? {
        val basePath = project.basePath ?: return null
        val gitConfigPath = findGitConfigPath(Paths.get(basePath)) ?: return null
        val remoteUrl = parseRemoteUrlFromConfig(gitConfigPath) ?: return null
        val normalized = GitUrlNormalizer.normalize(remoteUrl) ?: return null
        return GitRemoteInfo(rawUrl = remoteUrl, normalizedUrl = normalized)
    }

    private fun findGitConfigPath(startPath: Path): Path? {
        var current: Path? = startPath
        repeat(25) {
            if (current == null) return null
            val gitEntry = current!!.resolve(".git")
            if (gitEntry.isDirectory()) {
                val config = gitEntry.resolve("config")
                if (config.isRegularFile()) return config
            } else if (gitEntry.isRegularFile()) {
                val gitDir = resolveGitDirFromFile(gitEntry, current!!) ?: return@repeat
                val config = gitDir.resolve("config")
                if (config.isRegularFile()) return config
            }
            current = current!!.parent
        }
        return null
    }

    private fun resolveGitDirFromFile(gitFile: Path, workTree: Path): Path? {
        val firstLine = runCatching { Files.readAllLines(gitFile).firstOrNull() }.getOrNull() ?: return null
        val value = firstLine.trim().removePrefix("gitdir:").trim()
        if (value.isBlank()) return null
        val path = Paths.get(value)
        return if (path.isAbsolute) path.normalize() else workTree.resolve(path).normalize()
    }

    private fun parseRemoteUrlFromConfig(configPath: Path): String? {
        val lines = runCatching { Files.readAllLines(configPath) }.getOrNull() ?: return null

        val urlsByRemote = linkedMapOf<String, String>()
        var currentRemote: String? = null
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue

            val remoteMatch = REMOTE_SECTION_REGEX.matchEntire(line)
            if (remoteMatch != null) {
                currentRemote = remoteMatch.groupValues[1]
                continue
            }
            if (line.startsWith("[") && line.endsWith("]")) {
                currentRemote = null
                continue
            }

            if (currentRemote != null) {
                val urlMatch = REMOTE_URL_REGEX.matchEntire(line)
                if (urlMatch != null) {
                    urlsByRemote.putIfAbsent(currentRemote!!, urlMatch.groupValues[1].trim())
                }
            }
        }

        return urlsByRemote["origin"] ?: urlsByRemote.values.firstOrNull()
    }

    private val REMOTE_SECTION_REGEX = Regex("^\\[remote\\s+\"([^\"]+)\"\\]$")
    private val REMOTE_URL_REGEX = Regex("^url\\s*=\\s*(.+)$")
}

object GitUrlNormalizer {

    fun normalize(rawUrl: String): String? {
        val url = rawUrl.trim().trimEnd('/')
        if (url.isBlank()) return null

        return parseAsScpLike(url)
            ?: parseAsUri(url)
    }

    private fun parseAsScpLike(url: String): String? {
        if (url.contains("://")) return null
        val colonIndex = url.indexOf(':')
        if (colonIndex <= 0) return null

        val hostPart = url.substring(0, colonIndex).substringAfter('@').trim()
        val pathPart = url.substring(colonIndex + 1).trim()
        if (hostPart.isBlank() || pathPart.isBlank()) return null

        val host = hostPart.lowercase()
        val path = normalizePath(pathPart)
        if (path.isBlank()) return null

        return "$host/$path".lowercase()
    }

    private fun parseAsUri(url: String): String? =
        runCatching {
            val uri = URI(url)
            val host = uri.host?.trim()?.lowercase() ?: return null
            val path = normalizePath(uri.path ?: "")
            if (path.isBlank()) return null
            "$host/$path".lowercase()
        }.getOrNull()

    private fun normalizePath(path: String): String =
        path.trim()
            .trimStart('/')
            .trimEnd('/')
            .removeSuffix(".git")
}
