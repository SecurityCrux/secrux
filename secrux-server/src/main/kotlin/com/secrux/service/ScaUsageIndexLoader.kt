package com.secrux.service

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path

@Service
class ScaUsageIndexLoader(
    private val objectMapper: ObjectMapper
) {
    fun load(path: Path): ScaUsageIndex? {
        if (!Files.exists(path) || Files.isDirectory(path)) {
            return null
        }
        return runCatching { objectMapper.readValue(path.toFile(), ScaUsageIndex::class.java) }.getOrNull()
    }
}

