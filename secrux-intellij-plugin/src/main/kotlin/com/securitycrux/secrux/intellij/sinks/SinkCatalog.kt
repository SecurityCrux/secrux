package com.securitycrux.secrux.intellij.sinks

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.skgroup.codeauditassistant.enums.SubVulnerabilityDefinition
import org.skgroup.codeauditassistant.enums.SubVulnerabilityType
import org.skgroup.codeauditassistant.utils.SinkList
import java.io.File

object SinkCatalog {
    const val ID_BUILTIN_ALL = "builtin_all"
    const val ID_CUSTOM_FILE = "custom_file"

    private val log = Logger.getInstance(SinkCatalog::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    fun builtinAll(): List<SubVulnerabilityDefinition> =
        SinkList.ALL_SUB_VUL_DEFINITIONS.toList()

    fun load(
        project: Project,
        catalogId: String,
        catalogFilePath: String
    ): List<SubVulnerabilityDefinition> =
        when (catalogId) {
            ID_CUSTOM_FILE -> loadFromFile(project, catalogFilePath)
            else -> builtinAll()
        }

    private fun loadFromFile(
        project: Project,
        rawPath: String
    ): List<SubVulnerabilityDefinition> {
        val file = resolveFile(project, rawPath)
        val body = runCatching { file.readText(Charsets.UTF_8) }
            .getOrElse { throw SinkCatalogLoadException("error.sinkCatalogReadFailed", arrayOf(file.path), it) }

        val root =
            runCatching { json.parseToJsonElement(body).jsonObject }
                .getOrElse { throw SinkCatalogLoadException("error.sinkCatalogInvalidJson", arrayOf(file.path), it) }

        val formatVersion = root["formatVersion"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1
        if (formatVersion != 1) {
            throw SinkCatalogLoadException("error.sinkCatalogUnsupportedFormatVersion", arrayOf(formatVersion))
        }

        val defsArray = root["definitions"]?.jsonArray
            ?: throw SinkCatalogLoadException("error.sinkCatalogNoDefinitions", arrayOf(file.path))

        val definitions = mutableListOf<SubVulnerabilityDefinition>()
        for (elem in defsArray) {
            val obj = runCatching { elem.jsonObject }.getOrNull() ?: continue
            val subTypeName = obj["subType"]?.jsonPrimitive?.content ?: continue
            val subType =
                runCatching { SubVulnerabilityType.valueOf(subTypeName) }
                    .onFailure { log.warn("Unknown subType in sink catalog: $subTypeName") }
                    .getOrNull()
                    ?: continue

            val methodSinks =
                obj["methodSinks"]?.jsonObject
                    ?.mapValues { (_, v) ->
                        v.jsonArray.mapNotNull { it.contentOrNull() }.toSet()
                    }
                    .orEmpty()

            val constructorSinks =
                obj["constructorSinks"]?.jsonArray
                    ?.mapNotNull { it.contentOrNull() }
                    ?.toSet()
                    .orEmpty()

            val isCall = obj["isCall"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true

            definitions.add(
                SubVulnerabilityDefinition(
                    subType = subType,
                    methodSinks = methodSinks,
                    constructorSinks = constructorSinks,
                    isCall = isCall
                )
            )
        }

        if (definitions.isEmpty()) {
            throw SinkCatalogLoadException("error.sinkCatalogNoValidDefinitions", arrayOf(file.path))
        }

        return definitions
    }

    private fun resolveFile(
        project: Project,
        rawPath: String
    ): File {
        val trimmed = rawPath.trim()
        if (trimmed.isEmpty()) {
            throw SinkCatalogLoadException("error.sinkCatalogFilePathEmpty")
        }

        val candidate = File(trimmed)
        val file =
            if (candidate.isAbsolute) {
                candidate
            } else {
                val basePath =
                    project.basePath
                        ?: throw SinkCatalogLoadException("error.sinkCatalogProjectBasePathMissing", arrayOf(trimmed))
                File(basePath, trimmed)
            }

        if (!file.isFile) {
            throw SinkCatalogLoadException("error.sinkCatalogFileNotFound", arrayOf(file.path))
        }

        return file
    }

    private fun JsonElement.contentOrNull(): String? =
        runCatching { jsonPrimitive.content }.getOrNull()
}
