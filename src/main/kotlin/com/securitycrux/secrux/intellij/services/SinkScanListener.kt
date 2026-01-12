package com.securitycrux.secrux.intellij.services

import com.intellij.util.messages.Topic
import com.securitycrux.secrux.intellij.sinks.SinkMatch

fun interface SinkScanListener {

    fun onSinksUpdated(matches: List<SinkMatch>)

    companion object {
        val TOPIC: Topic<SinkScanListener> =
            Topic.create("Secrux Sink Scan Updates", SinkScanListener::class.java)
    }
}

