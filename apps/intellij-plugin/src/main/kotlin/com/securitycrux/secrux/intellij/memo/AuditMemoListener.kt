package com.securitycrux.secrux.intellij.memo

import com.intellij.util.messages.Topic

fun interface AuditMemoListener {

    fun onMemoUpdated(items: List<AuditMemoItemState>)

    companion object {
        val TOPIC: Topic<AuditMemoListener> =
            Topic.create("Secrux Audit Memo Updates", AuditMemoListener::class.java)
    }
}

