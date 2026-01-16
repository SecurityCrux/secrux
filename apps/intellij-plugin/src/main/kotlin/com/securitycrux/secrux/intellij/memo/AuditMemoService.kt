package com.securitycrux.secrux.intellij.memo

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiField
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiTreeUtil
import com.securitycrux.secrux.intellij.i18n.SecruxBundle
import com.securitycrux.secrux.intellij.settings.SecruxProjectSettings
import com.securitycrux.secrux.intellij.util.SecruxNotifications
import java.util.UUID

@Service(Service.Level.PROJECT)
class AuditMemoService(
    private val project: Project
) {

    fun getItems(): List<AuditMemoItemState> =
        SecruxProjectSettings.getInstance(project).state.memoItems.toList()

    fun addFromCurrentEditor(): AuditMemoItemState? {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor
        if (editor == null) {
            SecruxNotifications.error(project, SecruxBundle.message("error.noActiveEditor"))
            return null
        }
        return addFromEditor(editor)
    }

    fun addFromEditor(editor: Editor): AuditMemoItemState? {
        val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.document) ?: return null
        return addFromPsiFile(psiFile, editor.caretModel.offset)
    }

    fun addFromPsiFile(psiFile: PsiFile, offset: Int): AuditMemoItemState? {
        val file = psiFile.virtualFile ?: return null
        val title = buildTitle(psiFile, offset)
        val (line, column) = computeLineColumn(psiFile, offset)
        val relativePath = toProjectRelativePath(file.path)

        val state = SecruxProjectSettings.getInstance(project).state

        val existing =
            state.memoItems.firstOrNull { item ->
                item.filePath == relativePath && item.offset == offset && item.title == title
            }
        if (existing != null) return existing

        val item =
            AuditMemoItemState(
                id = UUID.randomUUID().toString(),
                title = title,
                filePath = relativePath,
                line = line,
                column = column,
                offset = offset,
                createdAtEpochMs = System.currentTimeMillis()
            )

        state.memoItems.add(0, item)
        publish()
        SecruxNotifications.info(project, SecruxBundle.message("notification.memoAdded", title))
        return item
    }

    fun removeById(id: String) {
        val state = SecruxProjectSettings.getInstance(project).state
        val removed = state.memoItems.removeIf { it.id == id }
        if (!removed) return
        publish()
    }

    fun clear() {
        val state = SecruxProjectSettings.getInstance(project).state
        if (state.memoItems.isEmpty()) return
        state.memoItems.clear()
        publish()
    }

    private fun publish() {
        val items = getItems()
        ApplicationManager.getApplication().invokeLater {
            project.messageBus.syncPublisher(AuditMemoListener.TOPIC).onMemoUpdated(items)
        }
    }

    private fun buildTitle(psiFile: PsiFile, offset: Int): String {
        val element = psiFile.findElementAt(offset) ?: psiFile

        val method = PsiTreeUtil.getParentOfType(element, PsiMethod::class.java, false)
        if (method != null) {
            val className = method.containingClass?.qualifiedName
            val name = if (method.isConstructor) "<init>" else method.name
            val params = method.parameterList.parametersCount
            return if (className != null) "$className#$name/$params" else "$name/$params"
        }

        val field = PsiTreeUtil.getParentOfType(element, PsiField::class.java, false)
        if (field != null) {
            val className = field.containingClass?.qualifiedName
            val name = field.name
            return if (className != null) "$className#$name" else name
        }

        val namedElement =
            PsiTreeUtil.getParentOfType(element, com.intellij.psi.PsiNamedElement::class.java, false)
        val name = namedElement?.name?.takeIf { it.isNotBlank() }
        if (name != null) return name

        return element.text?.trim()?.replace('\n', ' ')?.take(120).orEmpty().ifBlank { psiFile.name }
    }

    private fun computeLineColumn(psiFile: PsiFile, offset: Int): Pair<Int, Int> {
        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: return 0 to 0
        val safeOffset = offset.coerceIn(0, document.textLength)
        val lineIndex = document.getLineNumber(safeOffset)
        val lineStart = document.getLineStartOffset(lineIndex)
        val line = lineIndex + 1
        val column = (safeOffset - lineStart) + 1
        return line to column
    }

    private fun toProjectRelativePath(absolutePath: String): String {
        val basePath = project.basePath ?: return absolutePath
        return absolutePath.removePrefix(basePath).removePrefix("/")
    }

    companion object {
        fun getInstance(project: Project): AuditMemoService = project.service()
    }
}

