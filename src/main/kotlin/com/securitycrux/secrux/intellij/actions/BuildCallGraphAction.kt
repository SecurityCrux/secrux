package com.securitycrux.secrux.intellij.actions

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.securitycrux.secrux.intellij.callgraph.CallGraphService
import com.securitycrux.secrux.intellij.i18n.SecruxBundle

class BuildCallGraphAction :
    DumbAwareAction(SecruxBundle.message("action.buildCallGraph")) {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        CallGraphService.getInstance(project).buildCallGraph()
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }
}

