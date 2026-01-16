package com.securitycrux.secrux.intellij.util

import com.intellij.openapi.project.Project
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

data class GitCommitSummary(
    val commitId: String,
    val subject: String? = null,
)

object GitCli {

    fun currentBranch(project: Project): String? {
        val output = runGit(project, listOf("rev-parse", "--abbrev-ref", "HEAD"))
        val value = output?.firstOrNull()?.trim().orEmpty()
        if (value.isNotBlank() && value != "HEAD") return value
        return GitFs.readHead(project)?.branch
    }

    fun headCommit(project: Project): String? {
        val output = runGit(project, listOf("rev-parse", "HEAD"))
        val value = output?.firstOrNull()?.trim().orEmpty()
        if (value.matches(HEX_COMMIT_REGEX)) return value
        return GitFs.readHead(project)?.commitId
    }

    fun listBranches(project: Project): List<String> {
        val output = runGit(project, listOf("branch", "--format=%(refname:short)"))
        val branches =
            output
                ?.asSequence()
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() }
                ?.distinct()
                ?.sorted()
                ?.toList()
        if (!branches.isNullOrEmpty()) return branches
        return GitFs.listBranches(project)
    }

    fun listCommits(project: Project, ref: String, limit: Int = 50): List<GitCommitSummary> {
        val output = runGit(project, listOf("log", "-n", limit.toString(), "--pretty=format:%H\t%s", ref))
        val commits =
            output
                ?.mapNotNull { line ->
                    val trimmed = line.trim()
                    if (trimmed.isBlank()) return@mapNotNull null
                    val parts = trimmed.split('\t', limit = 2)
                    val commitId = parts.getOrNull(0)?.trim()?.takeIf { it.matches(HEX_COMMIT_REGEX) } ?: return@mapNotNull null
                    val subject = parts.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
                    GitCommitSummary(commitId = commitId, subject = subject)
                }.orEmpty()
        if (commits.isNotEmpty()) return commits

        val head = GitFs.readHead(project)?.commitId?.takeIf { it.matches(HEX_COMMIT_REGEX) }
        return head?.let { listOf(GitCommitSummary(commitId = it)) } ?: emptyList()
    }

    private fun runGit(project: Project, args: List<String>): List<String>? {
        val basePath = project.basePath ?: return null
        return runGit(Paths.get(basePath), args)
    }

    private fun runGit(workingDir: Path, args: List<String>): List<String>? {
        val command = mutableListOf("git")
        command.addAll(args)

        val process =
            runCatching {
                ProcessBuilder(command)
                    .directory(workingDir.toFile())
                    .redirectErrorStream(true)
                    .start()
            }.getOrNull() ?: return null

        val output =
            runCatching {
                BufferedReader(InputStreamReader(process.inputStream, StandardCharsets.UTF_8)).use { it.readLines() }
            }.getOrNull() ?: emptyList()

        val finished = runCatching { process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS) }.getOrNull() == true
        if (!finished) {
            process.destroyForcibly()
            return null
        }
        if (process.exitValue() != 0) return null
        return output
    }

    private object GitFs {

        fun readHead(project: Project): HeadInfo? {
            val basePath = project.basePath ?: return null
            val gitDir = resolveGitDir(Paths.get(basePath)) ?: return null
            val headFile = gitDir.resolve("HEAD")
            val headLine = runCatching { Files.readAllLines(headFile).firstOrNull() }.getOrNull()?.trim().orEmpty()
            if (headLine.isBlank()) return null

            if (headLine.startsWith("ref:", ignoreCase = true)) {
                val ref = headLine.removePrefix("ref:").trim()
                val branch = ref.removePrefix("refs/heads/").takeIf { it.isNotBlank() }
                val commitId = readRefCommit(gitDir, ref)
                return HeadInfo(branch = branch, commitId = commitId)
            }

            val commitId = headLine.takeIf { it.matches(HEX_COMMIT_REGEX) }
            return HeadInfo(branch = null, commitId = commitId)
        }

        fun listBranches(project: Project): List<String> {
            val basePath = project.basePath ?: return emptyList()
            val gitDir = resolveGitDir(Paths.get(basePath)) ?: return emptyList()

            val branches = linkedSetOf<String>()
            val headsDir = gitDir.resolve("refs").resolve("heads")
            if (headsDir.isDirectory()) {
                runCatching {
                    Files.walk(headsDir).use { stream ->
                        stream.filter { Files.isRegularFile(it) }.forEach { file ->
                            val rel = headsDir.relativize(file).toString().replace('\\', '/')
                            if (rel.isNotBlank()) branches.add(rel)
                        }
                    }
                }
            }

            val packedRefs = gitDir.resolve("packed-refs")
            if (packedRefs.isRegularFile()) {
                runCatching {
                    Files.readAllLines(packedRefs).forEach { line ->
                        val trimmed = line.trim()
                        if (trimmed.isBlank() || trimmed.startsWith("#") || trimmed.startsWith("^")) return@forEach
                        val parts = trimmed.split(' ', limit = 2)
                        if (parts.size != 2) return@forEach
                        val ref = parts[1].trim()
                        if (!ref.startsWith("refs/heads/")) return@forEach
                        val name = ref.removePrefix("refs/heads/").trim()
                        if (name.isNotBlank()) branches.add(name)
                    }
                }
            }

            return branches.toList().sorted()
        }

        private fun resolveGitDir(startPath: Path): Path? {
            var current: Path? = startPath
            repeat(25) {
                if (current == null) return null
                val gitEntry = current!!.resolve(".git")
                if (gitEntry.isDirectory()) {
                    return gitEntry
                } else if (gitEntry.isRegularFile()) {
                    return resolveGitDirFromFile(gitEntry, current!!)
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

        private fun readRefCommit(gitDir: Path, ref: String): String? {
            val refFile = gitDir.resolve(ref)
            if (refFile.isRegularFile()) {
                val commit = runCatching { Files.readAllLines(refFile).firstOrNull() }.getOrNull()?.trim().orEmpty()
                return commit.takeIf { it.matches(HEX_COMMIT_REGEX) }
            }

            val packedRefs = gitDir.resolve("packed-refs")
            if (!packedRefs.isRegularFile()) return null
            val lines = runCatching { Files.readAllLines(packedRefs) }.getOrNull() ?: return null
            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.isBlank() || trimmed.startsWith("#") || trimmed.startsWith("^")) continue
                val parts = trimmed.split(' ', limit = 2)
                if (parts.size != 2) continue
                if (parts[1].trim() == ref) {
                    val commitId = parts[0].trim()
                    return commitId.takeIf { it.matches(HEX_COMMIT_REGEX) }
                }
            }
            return null
        }
    }

    private data class HeadInfo(
        val branch: String?,
        val commitId: String?,
    )

    private const val COMMAND_TIMEOUT_SECONDS = 8L
    private val HEX_COMMIT_REGEX = Regex("^[0-9a-fA-F]{7,40}$")
}

