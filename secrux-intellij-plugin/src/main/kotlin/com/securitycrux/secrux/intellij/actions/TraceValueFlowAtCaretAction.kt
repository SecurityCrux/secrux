package com.securitycrux.secrux.intellij.actions

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.PsiField
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiVariable
import com.securitycrux.secrux.intellij.callgraph.MethodRef
import com.securitycrux.secrux.intellij.i18n.SecruxBundle
import com.securitycrux.secrux.intellij.services.CustomValueFlowRequest
import com.securitycrux.secrux.intellij.services.CustomValueFlowService
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UExpression
import org.jetbrains.uast.UParenthesizedExpression
import org.jetbrains.uast.UQualifiedReferenceExpression
import org.jetbrains.uast.USimpleNameReferenceExpression
import org.jetbrains.uast.UThisExpression
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.toUElementOfType

class TraceValueFlowAtCaretAction :
    DumbAwareAction(SecruxBundle.message("action.traceValueFlowAtCaret")) {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR)
        if (editor == null) {
            Messages.showErrorDialog(project, SecruxBundle.message("error.noActiveEditor"), SecruxBundle.message("dialog.title"))
            return
        }
        val psiFile = e.getData(CommonDataKeys.PSI_FILE) ?: return
        val file = psiFile.virtualFile ?: return

        val caretOffset = editor.caretModel.offset
        val element = psiFile.findElementAt(caretOffset) ?: return

        val expr = findUExpression(element) ?: run {
            Messages.showErrorDialog(project, SecruxBundle.message("error.valueFlow.noToken"), SecruxBundle.message("dialog.title"))
            return
        }

        val enclosing = findEnclosingMethod(expr) ?: run {
            Messages.showErrorDialog(project, SecruxBundle.message("error.valueFlow.noEnclosingMethod"), SecruxBundle.message("dialog.title"))
            return
        }

        val methodRef = enclosing.javaPsi.toMethodRefOrNull() ?: run {
            Messages.showErrorDialog(project, SecruxBundle.message("error.valueFlow.noEnclosingMethod"), SecruxBundle.message("dialog.title"))
            return
        }

        val token = valueTokenForExpression(enclosing, methodRef, expr) ?: run {
            Messages.showErrorDialog(project, SecruxBundle.message("error.valueFlow.noToken"), SecruxBundle.message("dialog.title"))
            return
        }

        val startOffset = expr.sourcePsi?.textRange?.startOffset ?: caretOffset
        CustomValueFlowService.getInstance(project)
            .request(
                CustomValueFlowRequest(
                    file = file,
                    startOffset = startOffset,
                    method = methodRef,
                    token = token,
                )
            )

        ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)?.activate(null)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null && e.getData(CommonDataKeys.EDITOR) != null
    }

    private fun findUExpression(element: com.intellij.psi.PsiElement): UExpression? {
        var cur: com.intellij.psi.PsiElement? = element
        repeat(12) {
            cur?.toUElementOfType<UExpression>()?.let { return it }
            cur = cur?.parent
        }
        return null
    }

    private fun findEnclosingMethod(node: UElement): UMethod? {
        var parent: UElement? = node.uastParent
        while (parent != null && parent !is UMethod) {
            parent = parent.uastParent
        }
        return parent as? UMethod
    }

    private fun PsiMethod.toMethodRefOrNull(): MethodRef? {
        val classFqn = containingClass?.qualifiedName ?: return null
        val name = if (isConstructor) "<init>" else name
        val paramCount = parameterList.parametersCount
        return MethodRef(classFqn = classFqn, name = name, paramCount = paramCount)
    }

    private fun tokenForPsiVariable(enclosingMethod: PsiMethod, variable: PsiVariable): String? {
        val name = variable.name?.takeIf { it.isNotBlank() } ?: return null
        if (variable is com.intellij.psi.PsiParameter) {
            val params = enclosingMethod.parameterList.parameters
            val byIdentity = params.indexOfFirst { it == variable }
            val idx = if (byIdentity >= 0) byIdentity else params.indexOfFirst { it.name == name }
            return idx.takeIf { it >= 0 }?.let { "PARAM:$it" }
        }
        return "LOCAL:$name"
    }

    private fun fieldToken(ownerFqn: String, fieldName: String, isStatic: Boolean, receiverToken: String?): String? {
        if (isStatic) return "STATIC:$ownerFqn#$fieldName"
        val recv = receiverToken?.trim()
        return when {
            recv == null || recv == "THIS" -> "THIS:$ownerFqn#$fieldName"
            recv.isBlank() || recv == "UNKNOWN" -> null
            else -> "HEAP($recv):$ownerFqn#$fieldName"
        }
    }

    private fun callResultTokenForCallExpression(node: UCallExpression): String? {
        val offset =
            node.sourcePsi?.textRange?.startOffset
                ?: node.methodIdentifier?.sourcePsi?.textRange?.startOffset
                ?: return null
        return "CALLRET@$offset"
    }

    private fun valueTokenForExpression(enclosingMethod: UMethod, methodRef: MethodRef, expr: UElement?): String? {
        val element = expr ?: return null
        return when (element) {
            is UParenthesizedExpression ->
                valueTokenForExpression(enclosingMethod, methodRef, element.expression)
            is UThisExpression -> "THIS"
            is UCallExpression -> callResultTokenForCallExpression(element)
            is USimpleNameReferenceExpression -> {
                val resolved = element.resolve()
                when (resolved) {
                    is PsiField -> {
                        val owner = resolved.containingClass?.qualifiedName ?: return null
                        fieldToken(
                            ownerFqn = owner,
                            fieldName = resolved.name,
                            isStatic = resolved.hasModifierProperty(PsiModifier.STATIC),
                            receiverToken = null,
                        )
                    }

                    is PsiVariable -> tokenForPsiVariable(enclosingMethod.javaPsi, resolved)

                    else -> null
                }
            }

            is UQualifiedReferenceExpression -> {
                val selectorCall = element.selector as? UCallExpression
                if (selectorCall != null) {
                    return callResultTokenForCallExpression(selectorCall)
                        ?: element.sourcePsi?.textRange?.startOffset?.let { "CALLRET@$it" }
                }
                val resolved = element.resolve() as? PsiField ?: return null
                val owner = resolved.containingClass?.qualifiedName ?: return null
                val receiverToken =
                    if (resolved.hasModifierProperty(PsiModifier.STATIC)) {
                        null
                    } else {
                        val recv = element.receiver ?: return null
                        valueTokenForExpression(enclosingMethod, methodRef, recv) ?: "UNKNOWN"
                    }
                fieldToken(
                    ownerFqn = owner,
                    fieldName = resolved.name,
                    isStatic = resolved.hasModifierProperty(PsiModifier.STATIC),
                    receiverToken = receiverToken,
                )
            }

            else -> null
        }
    }

    private companion object {
        private const val TOOL_WINDOW_ID = "Secrux Results"
    }
}
