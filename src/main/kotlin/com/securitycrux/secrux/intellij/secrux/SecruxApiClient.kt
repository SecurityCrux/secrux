package com.securitycrux.secrux.intellij.secrux

import com.intellij.util.io.HttpRequests
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class SecruxApiClient(
    private val baseUrl: String,
    private val token: String
) {

    fun ingestFindings(taskId: String, bodyJson: String): String {
        val url = "${baseUrl.trimEnd('/')}/tasks/${taskId.trim()}/findings"
        return HttpRequests.post(url, "application/json")
            .tuner { connection ->
                connection.setRequestProperty("Authorization", "Bearer $token")
                connection.setRequestProperty("Accept", "application/json")
            }.connect { request ->
                request.write(bodyJson)
                request.readString()
            }
    }

    fun triggerFindingAiReview(findingId: String, bodyJson: String? = null): String {
        val url = "${baseUrl.trimEnd('/')}/findings/${findingId.trim()}/ai-review"
        return HttpRequests.post(url, "application/json")
            .tuner { connection ->
                connection.setRequestProperty("Authorization", "Bearer $token")
                connection.setRequestProperty("Accept", "application/json")
            }.connect { request ->
                if (!bodyJson.isNullOrBlank()) {
                    request.write(bodyJson)
                }
                request.readString()
            }
    }

    fun getAiJob(jobId: String): String {
        val url = "${baseUrl.trimEnd('/')}/ai/jobs/${jobId.trim()}"
        return HttpRequests.request(url)
            .tuner { connection ->
                connection.setRequestProperty("Authorization", "Bearer $token")
                connection.setRequestProperty("Accept", "application/json")
            }.connect { request ->
                request.readString()
            }
    }

    fun getFindingDetail(findingId: String): String {
        val url = "${baseUrl.trimEnd('/')}/findings/${findingId.trim()}"
        return HttpRequests.request(url)
            .tuner { connection ->
                connection.setRequestProperty("Authorization", "Bearer $token")
                connection.setRequestProperty("Accept", "application/json")
            }.connect { request ->
                request.readString()
            }
    }

    fun listTasks(
        limit: Int,
        offset: Int,
        search: String? = null,
        status: String? = null,
        projectId: String? = null
    ): String {
        val queryParams = linkedMapOf<String, String>()
        queryParams["limit"] = limit.toString()
        queryParams["offset"] = offset.toString()
        if (!search.isNullOrBlank()) queryParams["search"] = search
        if (!status.isNullOrBlank()) queryParams["status"] = status
        if (!projectId.isNullOrBlank()) queryParams["projectId"] = projectId

        val query =
            queryParams.entries.joinToString("&") { (k, v) ->
                "${encode(k)}=${encode(v)}"
            }

        val url = "${baseUrl.trimEnd('/')}/tasks?$query"
        return HttpRequests.request(url)
            .tuner { connection ->
                connection.setRequestProperty("Authorization", "Bearer $token")
                connection.setRequestProperty("Accept", "application/json")
            }.connect { request ->
                request.readString()
            }
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8)
}
