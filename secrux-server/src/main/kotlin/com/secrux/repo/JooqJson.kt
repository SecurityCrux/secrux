package com.secrux.repo

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import org.jooq.JSONB

internal fun ObjectMapper.toJsonb(value: Any): JSONB = JSONB.jsonb(writeValueAsString(value))

internal fun ObjectMapper.toJsonbOrNull(value: Any?): JSONB? =
    value?.let { JSONB.jsonb(writeValueAsString(it)) }

private val objectMapType = object : TypeReference<Map<String, Any?>>() {}

internal fun ObjectMapper.readMapOrEmpty(raw: String?): Map<String, Any?> {
    if (raw.isNullOrBlank()) {
        return emptyMap()
    }
    return readValue(raw, objectMapType)
}

internal fun ObjectMapper.readMapOrEmpty(raw: JSONB?): Map<String, Any?> = readMapOrEmpty(raw?.data())
