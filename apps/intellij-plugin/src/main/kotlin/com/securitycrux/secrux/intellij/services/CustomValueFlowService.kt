package com.securitycrux.secrux.intellij.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
class CustomValueFlowService(
    private val project: Project,
) {
    @Volatile
    private var pendingRequest: CustomValueFlowRequest? = null

    fun request(request: CustomValueFlowRequest) {
        pendingRequest = request
        project.messageBus.syncPublisher(CustomValueFlowListener.TOPIC).onCustomValueFlowRequested(request)
    }

    fun consumePendingRequest(): CustomValueFlowRequest? {
        val value = pendingRequest
        pendingRequest = null
        return value
    }

    companion object {
        fun getInstance(project: Project): CustomValueFlowService = project.service()
    }
}

