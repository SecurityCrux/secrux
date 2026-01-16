package com.securitycrux.secrux.intellij.secrux

import com.intellij.util.io.HttpRequests
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class SecruxApiClient(
    private val baseUrl: String,
    private val token: String
) {

    fun createIdeAuditTask(bodyJson: String): String {
        val url = "${baseUrl.trimEnd('/')}/ideplugins/intellij/tasks"
        return HttpRequests.post(url, "application/json")
            .tuner { connection ->
                connection.setRequestProperty("Authorization", "Bearer $token")
                connection.setRequestProperty("Accept", "application/json")
            }.connect { request ->
                request.write(bodyJson)
                request.readString()
            }
    }

    fun runIdeTaskAiReview(taskId: String, bodyJson: String): String {
        val url = "${baseUrl.trimEnd('/')}/ideplugins/intellij/tasks/${taskId.trim()}/ai-review"
        return HttpRequests.post(url, "application/json")
            .tuner { connection ->
                connection.setRequestProperty("Authorization", "Bearer $token")
                connection.setRequestProperty("Accept", "application/json")
            }.connect { request ->
                request.write(bodyJson)
                request.readString()
            }
    }

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

    fun listFindings(
        taskId: String,
        limit: Int,
        offset: Int,
        search: String? = null,
        status: String? = null,
        severity: String? = null
    ): String {
        val queryParams = linkedMapOf<String, String>()
        queryParams["limit"] = limit.toString()
        queryParams["offset"] = offset.toString()
        if (!search.isNullOrBlank()) queryParams["search"] = search
        if (!status.isNullOrBlank()) queryParams["status"] = status
        if (!severity.isNullOrBlank()) queryParams["severity"] = severity

        val query =
            queryParams.entries.joinToString("&") { (k, v) ->
                "${encode(k)}=${encode(v)}"
            }

        val url = "${baseUrl.trimEnd('/')}/tasks/${taskId.trim()}/findings?$query"
        return HttpRequests.request(url)
            .tuner { connection ->
                connection.setRequestProperty("Authorization", "Bearer $token")
                connection.setRequestProperty("Accept", "application/json")
            }.connect { request ->
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

    fun updateFindingStatus(findingId: String, bodyJson: String): String {
        val url = "${baseUrl.trimEnd('/')}/findings/${findingId.trim()}"
        return HttpRequests.request(url)
            .tuner { connection ->
                if (connection is HttpURLConnection) {
                    connection.requestMethod = "PATCH"
                }
                connection.setRequestProperty("Authorization", "Bearer $token")
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("Content-Type", "application/json")
            }.connect { request ->
                request.write(bodyJson)
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
        projectId: String? = null,
        type: String? = null,
    ): String {
        val queryParams = linkedMapOf<String, String>()
        queryParams["limit"] = limit.toString()
        queryParams["offset"] = offset.toString()
        if (!search.isNullOrBlank()) queryParams["search"] = search
        if (!status.isNullOrBlank()) queryParams["status"] = status
        if (!projectId.isNullOrBlank()) queryParams["projectId"] = projectId
        if (!type.isNullOrBlank()) queryParams["type"] = type

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

    fun listProjects(): String {
        val url = "${baseUrl.trimEnd('/')}/projects"
        return HttpRequests.request(url)
            .tuner { connection ->
                connection.setRequestProperty("Authorization", "Bearer $token")
                connection.setRequestProperty("Accept", "application/json")
            }.connect { request ->
                request.readString()
            }
    }

    fun createProject(bodyJson: String): String {
        val url = "${baseUrl.trimEnd('/')}/projects"
        return HttpRequests.post(url, "application/json")
            .tuner { connection ->
                connection.setRequestProperty("Authorization", "Bearer $token")
                connection.setRequestProperty("Accept", "application/json")
            }.connect { request ->
                request.write(bodyJson)
                request.readString()
            }
    }

    fun listRepositories(projectId: String): String {
        val url = "${baseUrl.trimEnd('/')}/projects/${projectId.trim()}/repositories"
        return HttpRequests.request(url)
            .tuner { connection ->
                connection.setRequestProperty("Authorization", "Bearer $token")
                connection.setRequestProperty("Accept", "application/json")
            }.connect { request ->
                request.readString()
            }
    }

    fun createRepository(projectId: String, bodyJson: String): String {
        val url = "${baseUrl.trimEnd('/')}/projects/${projectId.trim()}/repositories"
        return HttpRequests.post(url, "application/json")
            .tuner { connection ->
                connection.setRequestProperty("Authorization", "Bearer $token")
                connection.setRequestProperty("Accept", "application/json")
            }.connect { request ->
                request.write(bodyJson)
                request.readString()
            }
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8)
}
