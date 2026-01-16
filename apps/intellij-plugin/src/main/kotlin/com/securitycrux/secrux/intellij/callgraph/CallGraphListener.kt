package com.securitycrux.secrux.intellij.callgraph

import com.intellij.util.messages.Topic

fun interface CallGraphListener {

    fun onCallGraphUpdated(graph: CallGraph?)

    companion object {
        val TOPIC: Topic<CallGraphListener> =
            Topic.create("Secrux Call Graph Updates", CallGraphListener::class.java)
    }
}
