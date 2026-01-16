package com.securitycrux.secrux.intellij.actions

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.securitycrux.secrux.intellij.i18n.SecruxBundle
import com.securitycrux.secrux.intellij.services.SinkScanService

class ClearHighlightsAction :
    DumbAwareAction(SecruxBundle.message("action.clearHighlights")) {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        SinkScanService.getInstance(project).clearHighlights()
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }
}

