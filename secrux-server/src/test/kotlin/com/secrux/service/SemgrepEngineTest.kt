package com.secrux.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.secrux.common.SecruxException
import com.secrux.config.SemgrepProperties
import com.secrux.support.CommandResult
import com.secrux.support.CommandRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

class SemgrepEngineTest {

    private val mapper = ObjectMapper().registerModule(KotlinModule.Builder().build())

    @Test
    fun `executes semgrep and parses sarif`() {
        val sarifPayload = """
            {
              "version": "2.1.0",
              "runs": [
                {
                  "results": [
                    {
                      "ruleId": "rule.one",
                      "level": "warning",
                      "message": { "text": "issue" },
                      "locations": [
                        {
                          "physicalLocation": {
                            "artifactLocation": { "uri": "src/App.kt" },
                            "region": { "startLine": 10 }
                          }
                        }
                      ],
                      "fingerprints": { "primary": "fp-1" }
                    }
                  ]
                }
              ]
            }
        """.trimIndent()
        val runner = RecordingRunner(sarifPayload, exitCode = 1)
        val engine = SemgrepEngine(runner, mapper, SemgrepProperties())

        val result = engine.run(listOf("src"))

        assertEquals(1, result.results.size)
        assertEquals("rule.one", result.results.first().ruleId)
        assertTrue(runner.lastCommand.contains("--sarif"))
        assertTrue(runner.lastCommand.contains("--config"))
    }

    @Test
    fun `throws when semgrep exits with error`() {
        val runner = object : CommandRunner {
            override fun run(
                command: List<String>,
                workingDirectory: Path?,
                timeout: Duration,
                environment: Map<String, String>
            ): CommandResult = CommandResult(2, "", "boom")
        }
        val engine = SemgrepEngine(runner, mapper, SemgrepProperties())

        assertThrows(SecruxException::class.java) {
            engine.run(listOf("src"))
        }
    }

    private class RecordingRunner(
        private val sarifPayload: String,
        private val exitCode: Int
    ) : CommandRunner {

        lateinit var lastCommand: List<String>

        override fun run(
            command: List<String>,
            workingDirectory: Path?,
            timeout: Duration,
            environment: Map<String, String>
        ): CommandResult {
            lastCommand = command
            val outputIndex = command.indexOf("--output").takeIf { it >= 0 }
                ?: error("Command missing --output flag")
            val outputPath = Path.of(command[outputIndex + 1])
            Files.writeString(outputPath, sarifPayload)
            return CommandResult(exitCode, "", "")
        }
    }
}
