package com.securitycrux.secrux.intellij.callgraph

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifierListOwner
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.securitycrux.secrux.intellij.i18n.SecruxBundle
import com.securitycrux.secrux.intellij.settings.SecruxProjectSettings
import com.securitycrux.secrux.intellij.util.SecruxNotifications
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UFile
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.toUElementOfType
import org.jetbrains.uast.visitor.AbstractUastVisitor
import java.io.File

@Service(Service.Level.PROJECT)
class CallGraphService(
    private val project: Project
) {

    private val log = Logger.getInstance(CallGraphService::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var lastGraph: CallGraph? = null

    fun getLastGraph(): CallGraph? = lastGraph

    fun buildCallGraph() {
        DumbService.getInstance(project).runWhenSmart {
            ProgressManager.getInstance().run(
                object : Task.Backgroundable(project, SecruxBundle.message("task.buildCallGraph"), true) {
                    override fun run(indicator: ProgressIndicator) {
                        val graph = buildGraphInternal(indicator)
                        lastGraph = graph
                        persistGraph(graph)

                        ApplicationManager.getApplication().invokeLater {
                            project.messageBus.syncPublisher(CallGraphListener.TOPIC).onCallGraphUpdated(graph)
                        }
                    }
                }
            )
        }
    }

    fun reloadCallGraph() {
        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, SecruxBundle.message("task.reloadCallGraph"), true) {
                override fun run(indicator: ProgressIndicator) {
                    indicator.isIndeterminate = true
                    val graph = loadGraph()
                    if (graph == null) {
                        SecruxNotifications.error(project, SecruxBundle.message("notification.callGraphCacheNotFound"))
                        return
                    }
                    lastGraph = graph
                    ApplicationManager.getApplication().invokeLater {
                        project.messageBus.syncPublisher(CallGraphListener.TOPIC).onCallGraphUpdated(graph)
                    }
                    SecruxNotifications.info(project, SecruxBundle.message("notification.callGraphLoaded"))
                }

                override fun onThrowable(error: Throwable) {
                    SecruxNotifications.error(
                        project,
                        SecruxBundle.message("notification.callGraphLoadFailed", error.message ?: error.javaClass.simpleName)
                    )
                }
            }
        )
    }

    fun clearCallGraphCache() {
        cacheFile()?.let { file ->
            runCatching { file.delete() }
        }
        lastGraph = null
        ApplicationManager.getApplication().invokeLater {
            project.messageBus.syncPublisher(CallGraphListener.TOPIC).onCallGraphUpdated(null)
        }
        SecruxNotifications.info(project, SecruxBundle.message("notification.callGraphCacheCleared"))
    }

    fun findCallerChains(
        target: MethodRef,
        maxDepth: Int,
        maxChains: Int
    ): List<List<MethodRef>> {
        val graph = lastGraph ?: return emptyList()
        if (maxDepth <= 0 || maxChains <= 0) return emptyList()

        val incoming = graph.incoming
        val results = mutableListOf<List<MethodRef>>()

        fun dfs(current: MethodRef, path: MutableList<MethodRef>, depth: Int) {
            if (results.size >= maxChains) return
            if (depth >= maxDepth) {
                results.add(path.reversed())
                return
            }

            val callers = incoming[current].orEmpty()
            if (callers.isEmpty()) {
                results.add(path.reversed())
                return
            }

            for (caller in callers) {
                if (caller in path) continue
                path.add(caller)
                dfs(caller, path, depth + 1)
                path.removeAt(path.lastIndex)
                if (results.size >= maxChains) return
            }
        }

        dfs(target, mutableListOf(target), depth = 0)
        return results
    }

    private fun persistGraph(graph: CallGraph) {
        val file = cacheFile() ?: return
        runCatching {
            file.parentFile?.mkdirs()
            val payload =
                buildJsonObject {
                    put("formatVersion", 1)
                    put("generatedAtEpochMs", System.currentTimeMillis())
                    putJsonObject("stats") {
                        put("filesScanned", graph.stats.filesScanned)
                        put("methodsIndexed", graph.stats.methodsIndexed)
                        put("callEdges", graph.stats.callEdges)
                        put("unresolvedCalls", graph.stats.unresolvedCalls)
                    }
                    putJsonArray("methods") {
                        for ((ref, loc) in graph.methods) {
                            val relPath = toProjectRelativePath(loc.file.path)
                            add(
                                buildJsonObject {
                                    put("id", ref.id)
                                    put("filePath", relPath)
                                    put("startOffset", loc.startOffset)
                                }
                            )
                        }
                    }
                    putJsonArray("outgoing") {
                        for ((caller, callees) in graph.outgoing) {
                            add(
                                buildJsonObject {
                                    put("caller", caller.id)
                                    putJsonArray("callees") {
                                        for (callee in callees) {
                                            add(JsonPrimitive(callee.id))
                                        }
                                    }
                                }
                            )
                        }
                    }
                    putJsonArray("entryPoints") {
                        for (entry in graph.entryPoints) {
                            add(JsonPrimitive(entry.id))
                        }
                    }
                }
            file.writeText(payload.toString(), Charsets.UTF_8)
        }.onFailure { e ->
            log.warn("Failed to persist call graph", e)
        }
    }

    private fun loadGraph(): CallGraph? {
        val file = cacheFile() ?: return null
        if (!file.exists()) return null

        val basePath = project.basePath ?: return null
        val body = file.readText(Charsets.UTF_8)
        val root = json.parseToJsonElement(body).jsonObject

        val methods =
            linkedMapOf<MethodRef, MethodLocation>()

        val methodsArray = root["methods"]?.jsonArray.orEmpty()
        for (entry in methodsArray) {
            val obj = entry.jsonObject
            val id = obj["id"]?.jsonPrimitive?.content ?: continue
            val filePath = obj["filePath"]?.jsonPrimitive?.content ?: continue
            val startOffset = obj["startOffset"]?.jsonPrimitive?.int ?: 0
            val ref = MethodRef.fromIdOrNull(id) ?: continue
            val vf = LocalFileSystem.getInstance().findFileByPath("$basePath/$filePath") ?: continue
            methods[ref] = MethodLocation(file = vf, startOffset = startOffset)
        }

        val outgoing =
            linkedMapOf<MethodRef, MutableSet<MethodRef>>()
        val incoming =
            linkedMapOf<MethodRef, MutableSet<MethodRef>>()

        val outgoingArray = root["outgoing"]?.jsonArray.orEmpty()
        for (entry in outgoingArray) {
            val obj = entry.jsonObject
            val callerId = obj["caller"]?.jsonPrimitive?.content ?: continue
            val caller = MethodRef.fromIdOrNull(callerId) ?: continue
            val callees = obj["callees"]?.jsonArray.orEmpty()
            for (calleeElem in callees) {
                val calleeId = calleeElem.jsonPrimitive.content
                val callee = MethodRef.fromIdOrNull(calleeId) ?: continue
                outgoing.getOrPut(caller) { linkedSetOf() }.add(callee)
                incoming.getOrPut(callee) { linkedSetOf() }.add(caller)
            }
        }

        val entryPoints =
            root["entryPoints"]?.jsonArray.orEmpty()
                .mapNotNull { elem -> MethodRef.fromIdOrNull(elem.jsonPrimitive.content) }
                .toSet()

        val statsObj = root["stats"]?.jsonObject
        val stats =
            if (statsObj != null) {
                CallGraphStats(
                    filesScanned = statsObj["filesScanned"]?.jsonPrimitive?.int ?: 0,
                    methodsIndexed = statsObj["methodsIndexed"]?.jsonPrimitive?.int ?: methods.size,
                    callEdges = statsObj["callEdges"]?.jsonPrimitive?.int ?: outgoing.values.sumOf { it.size },
                    unresolvedCalls = statsObj["unresolvedCalls"]?.jsonPrimitive?.int ?: 0
                )
            } else {
                CallGraphStats(
                    filesScanned = 0,
                    methodsIndexed = methods.size,
                    callEdges = outgoing.values.sumOf { it.size },
                    unresolvedCalls = 0
                )
            }

        return CallGraph(
            methods = methods.toMap(),
            outgoing = outgoing.mapValues { (_, v) -> v.toSet() },
            incoming = incoming.mapValues { (_, v) -> v.toSet() },
            entryPoints = entryPoints,
            stats = stats
        )
    }

    private fun cacheFile(): File? {
        val basePath = project.basePath ?: return null
        return File(basePath, ".idea/secrux/callgraph.json")
    }

    private fun toProjectRelativePath(absolutePath: String): String {
        val basePath = project.basePath ?: return absolutePath
        return absolutePath.removePrefix(basePath).removePrefix("/")
    }

    private fun buildGraphInternal(indicator: ProgressIndicator): CallGraph {
        val settings = SecruxProjectSettings.getInstance(project).state
        val excludedPathRegex =
            settings.excludedPathRegex.trim().takeIf { it.isNotEmpty() }?.let { pattern ->
                runCatching { Regex(pattern) }
                    .onFailure { e -> log.warn("Invalid excludedPathRegex: $pattern", e) }
                    .getOrNull()
            }

        val scope = GlobalSearchScope.projectScope(project)
        val psiManager = PsiManager.getInstance(project)

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

        val methods = linkedMapOf<MethodRef, MethodLocation>()
        val outgoing = linkedMapOf<MethodRef, MutableSet<MethodRef>>()
        val incoming = linkedMapOf<MethodRef, MutableSet<MethodRef>>()
        val entryPoints = linkedSetOf<MethodRef>()

        var unresolvedCalls = 0
        var edgeCount = 0

        val total = sourceFiles.size.coerceAtLeast(1)
        for ((i, file) in sourceFiles.withIndex()) {
            indicator.checkCanceled()
            indicator.text = SecruxBundle.message("progress.buildingCallGraphFile", file.name)
            indicator.fraction = i.toDouble() / total.toDouble()

            ReadAction.run<RuntimeException> {
                val psiFile = psiManager.findFile(file) ?: return@run
                val uFile = psiFile.toUElementOfType<UFile>() ?: return@run

                uFile.accept(
                    object : AbstractUastVisitor() {
                        override fun visitMethod(node: UMethod): Boolean {
                            val ref = node.javaPsi.toMethodRefOrNull() ?: return false
                            val offset = node.sourcePsi?.textRange?.startOffset ?: 0
                            methods.putIfAbsent(ref, MethodLocation(file = file, startOffset = offset))
                            outgoing.putIfAbsent(ref, linkedSetOf())
                            incoming.putIfAbsent(ref, linkedSetOf())
                            if (node.javaPsi.isSpringRequestHandler()) {
                                entryPoints.add(ref)
                            }
                            return false
                        }

                        override fun visitCallExpression(node: UCallExpression): Boolean {
                            val callerMethod = findEnclosingMethod(node) ?: return false
                            val callerRef = callerMethod.javaPsi.toMethodRefOrNull() ?: return false

                            val calleePsi = node.resolve()
                            if (calleePsi == null) {
                                unresolvedCalls++
                                return false
                            }
                            val calleeRef = calleePsi.toMethodRefOrNull() ?: return false

                            if (outgoing.getOrPut(callerRef) { linkedSetOf() }.add(calleeRef)) {
                                edgeCount++
                            }
                            incoming.getOrPut(calleeRef) { linkedSetOf() }.add(callerRef)

                            return false
                        }
                    }
                )
            }
        }

        val stats =
            CallGraphStats(
                filesScanned = sourceFiles.size,
                methodsIndexed = methods.size,
                callEdges = edgeCount,
                unresolvedCalls = unresolvedCalls
            )

        return CallGraph(
            methods = methods.toMap(),
            outgoing = outgoing.mapValues { (_, v) -> v.toSet() },
            incoming = incoming.mapValues { (_, v) -> v.toSet() },
            entryPoints = entryPoints.toSet(),
            stats = stats
        )
    }

    private fun relativePath(absolutePath: String): String {
        val basePath = project.basePath ?: return absolutePath
        return absolutePath.removePrefix(basePath).removePrefix("/")
    }

    private fun findEnclosingMethod(node: UElement): UMethod? {
        var parent: UElement? = node.uastParent
        while (parent != null && parent !is UMethod) {
            parent = parent.uastParent
        }
        return parent as? UMethod
    }

    private fun com.intellij.psi.PsiMethod.toMethodRefOrNull(): MethodRef? {
        val classFqn = containingClass?.qualifiedName ?: return null
        val name = if (isConstructor) "<init>" else name
        val paramCount = parameterList.parametersCount
        return MethodRef(classFqn = classFqn, name = name, paramCount = paramCount)
    }

    private fun PsiMethod.isSpringRequestHandler(): Boolean {
        val clazz = containingClass ?: return false
        if (!clazz.hasAnyAnnotation(SPRING_CONTROLLER_ANNOTATIONS)) return false
        if (!hasAnyAnnotation(SPRING_MAPPING_ANNOTATIONS)) return false
        return true
    }

    private fun PsiModifierListOwner.hasAnyAnnotation(annotationFqns: Set<String>): Boolean {
        val annotations = modifierList?.annotations ?: return false
        return annotations.any { ann -> ann.isAnnotationIn(annotationFqns) }
    }

    private fun PsiAnnotation.isAnnotationIn(annotationFqns: Set<String>): Boolean {
        val qName = qualifiedName
        if (qName != null) return qName in annotationFqns
        val short = nameReferenceElement?.referenceName ?: return false
        return annotationFqns.any { it.endsWith(".$short") }
    }

    companion object {
        private val SPRING_CONTROLLER_ANNOTATIONS =
            setOf(
                "org.springframework.stereotype.Controller",
                "org.springframework.web.bind.annotation.RestController"
            )

        private val SPRING_MAPPING_ANNOTATIONS =
            setOf(
                "org.springframework.web.bind.annotation.RequestMapping",
                "org.springframework.web.bind.annotation.GetMapping",
                "org.springframework.web.bind.annotation.PostMapping",
                "org.springframework.web.bind.annotation.PutMapping",
                "org.springframework.web.bind.annotation.DeleteMapping",
                "org.springframework.web.bind.annotation.PatchMapping"
            )

        fun getInstance(project: Project): CallGraphService = project.service()
    }
}
