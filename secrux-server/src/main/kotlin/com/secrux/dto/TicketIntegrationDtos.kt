package com.secrux.dto

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank

@Schema(name = "TicketProviderConfig")
data class TicketProviderConfigResponse(
    val provider: String,
    val baseUrl: String,
    val projectKey: String,
    val email: String,
    val issueTypeNames: Map<String, String>,
    val enabled: Boolean,
    val createdAt: String,
    val updatedAt: String?
)

@Schema(name = "TicketProviderConfigUpsertRequest")
@JsonIgnoreProperties(ignoreUnknown = true)
data class TicketProviderConfigUpsertRequest @JsonCreator constructor(
    @param:JsonProperty("baseUrl")
    @field:NotBlank
    val baseUrl: String,
    @param:JsonProperty("projectKey")
    @field:NotBlank
    val projectKey: String,
    @param:JsonProperty("email")
    @field:NotBlank
    val email: String,
    @param:JsonProperty("apiToken")
    val apiToken: String? = null,
    @param:JsonProperty("issueTypeNames")
    val issueTypeNames: Map<String, String>? = null,
    @param:JsonProperty("enabled")
    val enabled: Boolean = true
)

