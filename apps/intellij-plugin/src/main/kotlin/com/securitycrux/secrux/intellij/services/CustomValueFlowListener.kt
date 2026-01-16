package com.securitycrux.secrux.intellij.services

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.messages.Topic
import com.securitycrux.secrux.intellij.callgraph.MethodRef

data class CustomValueFlowRequest(
    val file: VirtualFile,
    val startOffset: Int,
    val method: MethodRef,
    val token: String,
)

fun interface CustomValueFlowListener {
    fun onCustomValueFlowRequested(request: CustomValueFlowRequest)

    companion object {
        val TOPIC: Topic<CustomValueFlowListener> =
            Topic.create("Secrux custom value-flow", CustomValueFlowListener::class.java)
    }
}

