package com.securitycrux.secrux.intellij.actions

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAwareAction
import com.securitycrux.secrux.intellij.i18n.SecruxBundle
import com.securitycrux.secrux.intellij.memo.AuditMemoService

class AddToAuditMemoAction :
    DumbAwareAction(SecruxBundle.message("action.addToAuditMemo")) {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val psiFile = e.getData(CommonDataKeys.PSI_FILE)
        val editor = e.getData(CommonDataKeys.EDITOR)
        val service = AuditMemoService.getInstance(project)

        when {
            psiFile != null && editor != null -> service.addFromPsiFile(psiFile, editor.caretModel.offset)
            editor != null -> service.addFromEditor(editor)
            else -> service.addFromCurrentEditor()
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }
}

