package com.secrux.support

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit

data class CommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String
)

interface CommandRunner {
    fun run(
        command: List<String>,
        workingDirectory: Path? = null,
        timeout: Duration = Duration.ZERO,
        environment: Map<String, String> = emptyMap()
    ): CommandResult
}

@Component
class ProcessCommandRunner : CommandRunner {

    private val log = LoggerFactory.getLogger(ProcessCommandRunner::class.java)

    override fun run(
        command: List<String>,
        workingDirectory: Path?,
        timeout: Duration,
        environment: Map<String, String>
    ): CommandResult {
        require(command.isNotEmpty()) { "Command must not be empty" }
        val builder = ProcessBuilder(command)
        workingDirectory?.let { builder.directory(it.toFile()) }
        if (environment.isNotEmpty()) {
            builder.environment().putAll(environment)
        }
        val process = builder.start()

        val finished = if (timeout.isZero) {
            process.waitFor()
            true
        } else {
            process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)
        }

        if (!finished) {
            process.destroyForcibly()
            throw IllegalStateException("Command timed out after $timeout: ${command.joinToString(" ")}")
        }

        val stdout = readStream(process.inputStream.bufferedReader(StandardCharsets.UTF_8))
        val stderr = readStream(process.errorStream.bufferedReader(StandardCharsets.UTF_8))
        val exitCode = process.exitValue()
        log.debug("event=command_finished exitCode={} command={}", exitCode, command.joinToString(" "))
        return CommandResult(exitCode, stdout, stderr)
    }

    private fun readStream(reader: BufferedReader): String =
        reader.use { it.readText() }
}
