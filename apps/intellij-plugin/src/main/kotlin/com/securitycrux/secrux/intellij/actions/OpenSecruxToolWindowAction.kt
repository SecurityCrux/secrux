package com.securitycrux.secrux.intellij.actions

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.wm.ToolWindowManager
import com.securitycrux.secrux.intellij.i18n.SecruxBundle

class OpenSecruxToolWindowAction :
    DumbAwareAction(SecruxBundle.message("action.openSecruxToolWindow")) {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)?.activate(null)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    companion object {
        private const val TOOL_WINDOW_ID = "Secrux"
    }
}

