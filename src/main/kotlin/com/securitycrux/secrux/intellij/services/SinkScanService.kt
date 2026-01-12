package com.securitycrux.secrux.intellij.services

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.ui.JBColor
import com.securitycrux.secrux.intellij.i18n.SecruxBundle
import com.securitycrux.secrux.intellij.sinks.SinkMatch
import com.securitycrux.secrux.intellij.sinks.SinkCatalog
import com.securitycrux.secrux.intellij.sinks.SinkCatalogLoadException
import com.securitycrux.secrux.intellij.sinks.SinkRegistry
import com.securitycrux.secrux.intellij.settings.SecruxProjectSettings
import com.securitycrux.secrux.intellij.settings.SecruxProjectSettingsState
import com.securitycrux.secrux.intellij.util.SecruxNotifications
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UFile
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.UastCallKind
import org.jetbrains.uast.toUElementOfType
import org.jetbrains.uast.visitor.AbstractUastVisitor

@Service(Service.Level.PROJECT)
class SinkScanService(
    private val project: Project
) {

    private val log = Logger.getInstance(SinkScanService::class.java)

    private val highlighters = mutableListOf<RangeHighlighter>()

    @Volatile
    private var lastMatches: List<SinkMatch> = emptyList()

    fun getLastMatches(): List<SinkMatch> = lastMatches

    fun clearHighlights() {
        ApplicationManager.getApplication().invokeLater { clearHighlightsNow() }
    }

    fun scanSinks() {
        DumbService.getInstance(project).runWhenSmart {
            ProgressManager.getInstance().run(
                object : Task.Backgroundable(project, SecruxBundle.message("task.scanSinks"), true) {
                    override fun run(indicator: ProgressIndicator) {
                        val matches = scanInternal(indicator)
                        lastMatches = matches

                        ApplicationManager.getApplication().invokeLater {
                            applyHighlights(matches)
                            project.messageBus.syncPublisher(SinkScanListener.TOPIC).onSinksUpdated(matches)
                        }
                    }
                }
            )
        }
    }

    private fun scanInternal(indicator: ProgressIndicator): List<SinkMatch> {
        val settings = SecruxProjectSettings.getInstance(project).state
        val sinkRegistry = buildSinkRegistry(settings)
        val excludedPathRegex =
            settings.excludedPathRegex.trim().takeIf { it.isNotEmpty() }?.let { pattern ->
                runCatching { Regex(pattern) }
                    .onFailure { e -> log.warn("Invalid excludedPathRegex: $pattern", e) }
                    .getOrNull()
            }
        val excludedSinkTypes = settings.excludedSinkTypes.map { it.trim() }.filter { it.isNotEmpty() }.toSet()

        val scope = GlobalSearchScope.projectScope(project)
        val sourceFiles =
            ReadAction.compute<List<VirtualFile>, RuntimeException> {
                val fileIndex = ProjectFileIndex.getInstance(project)
                val javaFiles = FilenameIndex.getAllFilesByExt(project, "java", scope)
                val kotlinFiles = FilenameIndex.getAllFilesByExt(project, "kt", scope)

                (javaFiles + kotlinFiles)
                    .distinct()
                    .filter { file ->
                        if (!fileIndex.isInSourceContent(file)) return@filter false
                        if (excludedPathRegex == null) return@filter true
                        val rel = relativePath(file.path)
                        !excludedPathRegex.containsMatchIn(rel)
                    }
            }

        val psiManager = PsiManager.getInstance(project)
        val psiDocumentManager = PsiDocumentManager.getInstance(project)

        val results = mutableListOf<SinkMatch>()
        val total = sourceFiles.size.coerceAtLeast(1)

        for ((i, file) in sourceFiles.withIndex()) {
            indicator.checkCanceled()
            indicator.text = SecruxBundle.message("progress.scanningFile", file.name)
            indicator.fraction = i.toDouble() / total.toDouble()

            ReadAction.run<RuntimeException> {
                val psiFile = psiManager.findFile(file) ?: return@run
                val document = psiDocumentManager.getDocument(psiFile)

                val uFile = psiFile.toUElementOfType<UFile>() ?: return@run
                uFile.accept(
                    object : AbstractUastVisitor() {
                        override fun visitCallExpression(node: UCallExpression): Boolean {
                            val resolved = node.resolve() ?: return false
                            val containingClassFqn = resolved.containingClass?.qualifiedName ?: return false

                            val isConstructor =
                                resolved.isConstructor ||
                                    node.kind == UastCallKind.CONSTRUCTOR_CALL
                            val targetParamCount = resolved.parameterList.parametersCount

                            val matchedTypes =
                                if (isConstructor) {
                                    sinkRegistry.matchConstructorCall(containingClassFqn)
                                } else {
                                    sinkRegistry.matchMethodCall(containingClassFqn, resolved.name)
                                }

                            if (matchedTypes.isEmpty()) return false

                            val filteredTypes =
                                if (excludedSinkTypes.isEmpty()) {
                                    matchedTypes.toList()
                                } else {
                                    matchedTypes.filterNot { it.name in excludedSinkTypes }
                                }

                            if (filteredTypes.isEmpty()) return false

                            val sourcePsi = node.sourcePsi ?: return false
                            val textRange = sourcePsi.textRange ?: return false
                            val startOffset = textRange.startOffset
                            val endOffset = textRange.endOffset

                            val (line, column) = computeLineColumn(document, startOffset)
                            val enclosingMethod = enclosingMethodFqn(node)

                            for (type in filteredTypes) {
                                results.add(
                                    SinkMatch(
                                        type = type,
                                        targetClassFqn = containingClassFqn,
                                        targetMember = if (isConstructor) "<init>" else resolved.name,
                                        targetParamCount = targetParamCount,
                                        file = file,
                                        startOffset = startOffset,
                                        endOffset = endOffset,
                                        line = line,
                                        column = column,
                                        enclosingMethodFqn = enclosingMethod
                                    )
                                )
                            }

                            return false
                        }
                    }
                )
            }
        }

        return results
    }

    private fun buildSinkRegistry(settings: SecruxProjectSettingsState): SinkRegistry {
        val definitions =
            try {
                SinkCatalog.load(
                    project = project,
                    catalogId = settings.sinkCatalogId,
                    catalogFilePath = settings.sinkCatalogFilePath
                )
            } catch (e: SinkCatalogLoadException) {
                SecruxNotifications.error(project, SecruxBundle.message(e.messageKey, *e.messageArgs))
                SinkCatalog.builtinAll()
            } catch (e: Exception) {
                SecruxNotifications.error(
                    project,
                    SecruxBundle.message("error.sinkCatalogLoadFailed", e.message ?: e.javaClass.simpleName)
                )
                SinkCatalog.builtinAll()
            }

        return SinkRegistry(definitions)
    }

    private fun relativePath(absolutePath: String): String {
        val basePath = project.basePath ?: return absolutePath
        return absolutePath.removePrefix(basePath).removePrefix("/")
    }

    private fun computeLineColumn(
        document: Document?,
        offset: Int
    ): Pair<Int, Int> {
        if (document == null) return 0 to 0
        val safeOffset = offset.coerceIn(0, document.textLength)
        val lineIndex = document.getLineNumber(safeOffset)
        val lineStart = document.getLineStartOffset(lineIndex)
        val line = lineIndex + 1
        val column = (safeOffset - lineStart) + 1
        return line to column
    }

    private fun enclosingMethodFqn(node: UCallExpression): String? {
        var parent = node.uastParent
        while (parent != null && parent !is UMethod) {
            parent = parent.uastParent
        }
        val method = parent as? UMethod ?: return null
        val psiMethod = method.javaPsi
        val className = psiMethod.containingClass?.qualifiedName ?: return psiMethod.name
        return "$className#${psiMethod.name}"
    }

    private fun applyHighlights(matches: List<SinkMatch>) {
        clearHighlightsNow()

        val fileDocumentManager = FileDocumentManager.getInstance()

        val attrs =
            TextAttributes(
                /* foregroundColor = */ null,
                /* backgroundColor = */ JBColor(0xFFF2CC, 0x5A4D00),
                /* effectColor = */ JBColor(0xFF9900, 0xFFCC66),
                /* effectType = */ EffectType.BOXED,
                /* fontType = */ 0
            )

        val byFile = matches.groupBy { it.file }
        for ((file, fileMatches) in byFile) {
            val document = fileDocumentManager.getDocument(file) ?: continue
            val markupModel = DocumentMarkupModel.forDocument(document, project, true)
            for (match in fileMatches.distinctBy { it.startOffset to it.endOffset }) {
                if (match.startOffset >= match.endOffset) continue
                val highlighter =
                    markupModel.addRangeHighlighter(
                        match.startOffset,
                        match.endOffset,
                        HighlighterLayer.SELECTION - 1,
                        attrs,
                        HighlighterTargetArea.EXACT_RANGE
                    )
                highlighters.add(highlighter)
            }
        }
    }

    private fun clearHighlightsNow() {
        highlighters.forEach { it.dispose() }
        highlighters.clear()
    }

    companion object {
        fun getInstance(project: Project): SinkScanService = project.service()
    }
}
