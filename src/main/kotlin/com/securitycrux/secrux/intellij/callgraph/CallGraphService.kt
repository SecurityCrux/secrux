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
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiField
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiModifierListOwner
import com.intellij.psi.PsiVariable
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.OverridingMethodsSearch
import com.intellij.psi.util.InheritanceUtil
import com.securitycrux.secrux.intellij.i18n.SecruxBundle
import com.securitycrux.secrux.intellij.settings.SecruxProjectSettings
import com.securitycrux.secrux.intellij.util.SecruxNotifications
import com.securitycrux.secrux.intellij.valueflow.BeanDefinition
import com.securitycrux.secrux.intellij.valueflow.BeanKind
import com.securitycrux.secrux.intellij.valueflow.CallSiteSummary
import com.securitycrux.secrux.intellij.valueflow.FieldLoad
import com.securitycrux.secrux.intellij.valueflow.FieldStore
import com.securitycrux.secrux.intellij.valueflow.FrameworkModelIndex
import com.securitycrux.secrux.intellij.valueflow.FrameworkModelStats
import com.securitycrux.secrux.intellij.valueflow.InjectionKind
import com.securitycrux.secrux.intellij.valueflow.InjectionPoint
import com.securitycrux.secrux.intellij.valueflow.MethodSummary
import com.securitycrux.secrux.intellij.valueflow.MethodSummaryIndex
import com.securitycrux.secrux.intellij.valueflow.MethodSummaryStats
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.jetbrains.uast.UBinaryExpression
import org.jetbrains.uast.UClass
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UFile
import org.jetbrains.uast.UQualifiedReferenceExpression
import org.jetbrains.uast.USimpleNameReferenceExpression
import org.jetbrains.uast.UThisExpression
import org.jetbrains.uast.UReturnExpression
import org.jetbrains.uast.UVariable
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.UastBinaryOperator
import org.jetbrains.uast.UParenthesizedExpression
import org.jetbrains.uast.toUElementOfType
import org.jetbrains.uast.visitor.AbstractUastVisitor
import java.io.File

@Service(Service.Level.PROJECT)
class CallGraphService(
    private val project: Project
) {

    private data class RawFieldAccess(
        val ownerFqn: String,
        val fieldName: String,
        val isStatic: Boolean,
        val receiverToken: String?,
        val isWrite: Boolean,
    )

    private data class RawFieldStore(
        val ownerFqn: String,
        val fieldName: String,
        val isStatic: Boolean,
        val receiverToken: String?,
        val valueToken: String?,
        val offset: Int?,
    )

    private data class RawFieldLoad(
        val ownerFqn: String,
        val fieldName: String,
        val isStatic: Boolean,
        val receiverToken: String?,
        val targetToken: String?,
        val offset: Int?,
    )

    private data class RawCallSite(
        val calleeId: String?,
        val callOffset: Int?,
        val receiverToken: String?,
        val argTokens: List<String?>,
        val resultToken: String?,
    )

    private data class BuildResult(
        val graph: CallGraph,
        val typeHierarchy: TypeHierarchyIndex,
        val methodSummaries: MethodSummaryIndex,
        val frameworkModel: FrameworkModelIndex,
    )

    private val log = Logger.getInstance(CallGraphService::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var lastGraph: CallGraph? = null

    @Volatile
    private var lastTypeHierarchy: TypeHierarchyIndex? = null

    @Volatile
    private var lastMethodSummaries: MethodSummaryIndex? = null

    @Volatile
    private var lastFrameworkModel: FrameworkModelIndex? = null

    fun getLastGraph(): CallGraph? = lastGraph

    fun getLastTypeHierarchy(): TypeHierarchyIndex? = lastTypeHierarchy

    fun getLastMethodSummaries(): MethodSummaryIndex? = lastMethodSummaries

    fun getLastFrameworkModel(): FrameworkModelIndex? = lastFrameworkModel

    private fun fieldKey(
        base: String,
        ownerFqn: String,
        fieldName: String,
    ): String = "$base:$ownerFqn#$fieldName"

    private class UnionFind {
        private val parent = hashMapOf<String, String>()
        private val rank = hashMapOf<String, Int>()

        private fun find(x: String): String {
            val p = parent[x]
            if (p == null) {
                parent[x] = x
                rank[x] = 0
                return x
            }
            if (p == x) return x
            val root = find(p)
            parent[x] = root
            return root
        }

        fun union(a: String, b: String) {
            val ra = find(a)
            val rb = find(b)
            if (ra == rb) return
            val rka = rank[ra] ?: 0
            val rkb = rank[rb] ?: 0
            when {
                rka < rkb -> parent[ra] = rb
                rka > rkb -> parent[rb] = ra
                else -> {
                    parent[rb] = ra
                    rank[ra] = rka + 1
                }
            }
        }

        fun connected(a: String, b: String): Boolean = find(a) == find(b)
    }

    fun buildCallGraph() {
        DumbService.getInstance(project).runWhenSmart {
            ProgressManager.getInstance().run(
                object : Task.Backgroundable(project, SecruxBundle.message("task.buildCallGraph"), true) {
                    override fun run(indicator: ProgressIndicator) {
                        val result = buildGraphInternal(indicator)
                        lastGraph = result.graph
                        lastTypeHierarchy = result.typeHierarchy
                        lastMethodSummaries = result.methodSummaries
                        lastFrameworkModel = result.frameworkModel
                        persistGraph(result.graph)
                        persistTypeHierarchy(result.typeHierarchy)
                        persistMethodSummaries(result.methodSummaries)
                        persistFrameworkModel(result.frameworkModel)

                        ApplicationManager.getApplication().invokeLater {
                            project.messageBus.syncPublisher(CallGraphListener.TOPIC).onCallGraphUpdated(result.graph)
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
                    val typeHierarchy = loadTypeHierarchy()
                    val methodSummaries = loadMethodSummaries()
                    val frameworkModel = loadFrameworkModel()
                    lastGraph = graph
                    lastTypeHierarchy = typeHierarchy
                    lastMethodSummaries = methodSummaries
                    lastFrameworkModel = frameworkModel
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
        typeHierarchyCacheFile()?.let { file ->
            runCatching { file.delete() }
        }
        lastTypeHierarchy = null
        methodSummaryCacheFile()?.let { file ->
            runCatching { file.delete() }
        }
        lastMethodSummaries = null
        frameworkModelCacheFile()?.let { file ->
            runCatching { file.delete() }
        }
        lastFrameworkModel = null
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
                    put("formatVersion", 3)
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
                    putJsonArray("edgeKinds") {
                        for ((edge, kind) in graph.edgeKinds) {
                            add(
                                buildJsonObject {
                                    put("caller", edge.caller.id)
                                    put("callee", edge.callee.id)
                                    put("kind", kind.name)
                                }
                            )
                        }
                    }
                    putJsonArray("callSites") {
                        for ((edge, loc) in graph.callSites) {
                            val relPath = toProjectRelativePath(loc.file.path)
                            add(
                                buildJsonObject {
                                    put("caller", edge.caller.id)
                                    put("callee", edge.callee.id)
                                    put("filePath", relPath)
                                    put("startOffset", loc.startOffset)
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

    private fun persistTypeHierarchy(index: TypeHierarchyIndex) {
        val file = typeHierarchyCacheFile() ?: return
        runCatching {
            file.parentFile?.mkdirs()
            val payload =
                buildJsonObject {
                    put("formatVersion", 1)
                    put("generatedAtEpochMs", System.currentTimeMillis())
                    putJsonObject("stats") {
                        put("typesIndexed", index.stats.typesIndexed)
                        put("edges", index.stats.edges)
                        put("implEdges", index.stats.implEdges)
                        put("exteEdges", index.stats.exteEdges)
                    }
                    putJsonArray("edges") {
                        for (edge in index.edges) {
                            add(
                                buildJsonObject {
                                    put("from", edge.from)
                                    put("to", edge.to)
                                    put("kind", edge.kind.name)
                                }
                            )
                        }
                    }
                }
            file.writeText(payload.toString(), Charsets.UTF_8)
        }.onFailure { e ->
            log.warn("Failed to persist type hierarchy", e)
        }
    }

    private fun persistMethodSummaries(index: MethodSummaryIndex) {
        val file = methodSummaryCacheFile() ?: return
        runCatching {
            file.parentFile?.mkdirs()
            val payload =
                buildJsonObject {
                    put("formatVersion", 2)
                    put("generatedAtEpochMs", System.currentTimeMillis())
                    putJsonObject("stats") {
                        put("methodsIndexed", index.stats.methodsIndexed)
                        put("methodsWithFieldAccess", index.stats.methodsWithFieldAccess)
                        put("fieldReads", index.stats.fieldReads)
                        put("fieldWrites", index.stats.fieldWrites)
                        put("distinctFields", index.stats.distinctFields)
                    }
                    putJsonArray("methods") {
                        for ((ref, summary) in index.summaries) {
                            add(
                                buildJsonObject {
                                    put("id", ref.id)
                                    putJsonArray("reads") {
                                        for (f in summary.fieldsRead) add(JsonPrimitive(f))
                                    }
                                    putJsonArray("writes") {
                                        for (f in summary.fieldsWritten) add(JsonPrimitive(f))
                                    }
                                    putJsonArray("stores") {
                                        for (s in summary.stores) {
                                            add(
                                                buildJsonObject {
                                                    put("targetField", s.targetField)
                                                    put("value", s.value)
                                                    s.offset?.let { put("offset", it) }
                                                }
                                            )
                                        }
                                    }
                                    putJsonArray("loads") {
                                        for (l in summary.loads) {
                                            add(
                                                buildJsonObject {
                                                    put("target", l.target)
                                                    put("sourceField", l.sourceField)
                                                    l.offset?.let { put("offset", it) }
                                                }
                                            )
                                        }
                                    }
                                    putJsonArray("calls") {
                                        for (c in summary.calls) {
                                            add(
                                                buildJsonObject {
                                                    c.calleeId?.let { put("calleeId", it) }
                                                    c.callOffset?.let { put("callOffset", it) }
                                                    c.receiver?.let { put("receiver", it) }
                                                    putJsonArray("args") {
                                                        for (a in c.args) add(JsonPrimitive(a))
                                                    }
                                                    c.result?.let { put("result", it) }
                                                }
                                            )
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            file.writeText(payload.toString(), Charsets.UTF_8)
        }.onFailure { e ->
            log.warn("Failed to persist method summaries", e)
        }
    }

    private fun persistFrameworkModel(index: FrameworkModelIndex) {
        val file = frameworkModelCacheFile() ?: return
        runCatching {
            file.parentFile?.mkdirs()
            val payload =
                buildJsonObject {
                    put("formatVersion", 1)
                    put("generatedAtEpochMs", System.currentTimeMillis())
                    putJsonObject("stats") {
                        put("beans", index.stats.beans)
                        put("injections", index.stats.injections)
                        put("classesWithBeans", index.stats.classesWithBeans)
                        put("classesWithInjections", index.stats.classesWithInjections)
                    }
                    putJsonArray("beans") {
                        for (bean in index.beans) {
                            add(
                                buildJsonObject {
                                    put("typeFqn", bean.typeFqn)
                                    put("kind", bean.kind.name)
                                    bean.source?.let { put("source", it) }
                                    bean.filePath?.let { put("filePath", it) }
                                    bean.startOffset?.let { put("startOffset", it) }
                                }
                            )
                        }
                    }
                    putJsonArray("injections") {
                        for (inj in index.injections) {
                            add(
                                buildJsonObject {
                                    put("ownerClassFqn", inj.ownerClassFqn)
                                    put("kind", inj.kind.name)
                                    put("targetTypeFqn", inj.targetTypeFqn)
                                    inj.name?.let { put("name", it) }
                                    inj.filePath?.let { put("filePath", it) }
                                    inj.startOffset?.let { put("startOffset", it) }
                                }
                            )
                        }
                    }
                }
            file.writeText(payload.toString(), Charsets.UTF_8)
        }.onFailure { e ->
            log.warn("Failed to persist framework model", e)
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

        val edgeKinds =
            linkedMapOf<CallEdge, CallEdgeKind>()
        val edgeKindsArray = root["edgeKinds"]?.jsonArray
        if (edgeKindsArray != null) {
            for (entry in edgeKindsArray) {
                val obj = entry.jsonObject
                val callerId = obj["caller"]?.jsonPrimitive?.content ?: continue
                val calleeId = obj["callee"]?.jsonPrimitive?.content ?: continue
                val kindStr = obj["kind"]?.jsonPrimitive?.content ?: continue
                val caller = MethodRef.fromIdOrNull(callerId) ?: continue
                val callee = MethodRef.fromIdOrNull(calleeId) ?: continue
                val kind = runCatching { CallEdgeKind.valueOf(kindStr) }.getOrNull() ?: continue
                edgeKinds.putIfAbsent(CallEdge(caller = caller, callee = callee), kind)
            }
        }
        if (edgeKinds.isEmpty()) {
            for ((caller, callees) in outgoing) {
                for (callee in callees) {
                    edgeKinds.putIfAbsent(CallEdge(caller = caller, callee = callee), CallEdgeKind.CALL)
                }
            }
        } else {
            for ((caller, callees) in outgoing) {
                for (callee in callees) {
                    edgeKinds.putIfAbsent(CallEdge(caller = caller, callee = callee), CallEdgeKind.CALL)
                }
            }
        }

        val callSites =
            linkedMapOf<CallEdge, CallSiteLocation>()
        val callSitesArray = root["callSites"]?.jsonArray.orEmpty()
        for (entry in callSitesArray) {
            val obj = entry.jsonObject
            val callerId = obj["caller"]?.jsonPrimitive?.content ?: continue
            val calleeId = obj["callee"]?.jsonPrimitive?.content ?: continue
            val filePath = obj["filePath"]?.jsonPrimitive?.content ?: continue
            val startOffset = obj["startOffset"]?.jsonPrimitive?.int ?: 0
            val caller = MethodRef.fromIdOrNull(callerId) ?: continue
            val callee = MethodRef.fromIdOrNull(calleeId) ?: continue
            val vf = LocalFileSystem.getInstance().findFileByPath("$basePath/$filePath") ?: continue
            callSites.putIfAbsent(CallEdge(caller = caller, callee = callee), CallSiteLocation(file = vf, startOffset = startOffset))
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
            callSites = callSites.toMap(),
            edgeKinds = edgeKinds.toMap(),
            entryPoints = entryPoints,
            stats = stats
        )
    }

    private fun loadTypeHierarchy(): TypeHierarchyIndex? {
        val file = typeHierarchyCacheFile() ?: return null
        if (!file.exists()) return null

        val body = file.readText(Charsets.UTF_8)
        val root = json.parseToJsonElement(body).jsonObject

        val edges =
            root["edges"]?.jsonArray.orEmpty()
                .mapNotNull { elem ->
                    val obj = elem.jsonObject
                    val from = obj["from"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    val to = obj["to"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    val kindStr = obj["kind"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    val kind = runCatching { TypeEdgeKind.valueOf(kindStr) }.getOrNull() ?: return@mapNotNull null
                    TypeHierarchyEdge(from = from, to = to, kind = kind)
                }
                .toSet()

        val statsObj = root["stats"]?.jsonObject
        val stats =
            if (statsObj != null) {
                TypeHierarchyStats(
                    typesIndexed = statsObj["typesIndexed"]?.jsonPrimitive?.int ?: 0,
                    edges = statsObj["edges"]?.jsonPrimitive?.int ?: edges.size,
                    implEdges = statsObj["implEdges"]?.jsonPrimitive?.int ?: edges.count { it.kind == TypeEdgeKind.IMPL },
                    exteEdges = statsObj["exteEdges"]?.jsonPrimitive?.int ?: edges.count { it.kind == TypeEdgeKind.EXTE },
                )
            } else {
                val types =
                    edges.asSequence()
                        .flatMap { e -> sequenceOf(e.from, e.to) }
                        .toSet()
                TypeHierarchyStats(
                    typesIndexed = types.size,
                    edges = edges.size,
                    implEdges = edges.count { it.kind == TypeEdgeKind.IMPL },
                    exteEdges = edges.count { it.kind == TypeEdgeKind.EXTE },
                )
            }

        return TypeHierarchyIndex(edges = edges, stats = stats)
    }

    private fun loadMethodSummaries(): MethodSummaryIndex? {
        val file = methodSummaryCacheFile() ?: return null
        if (!file.exists()) return null

        val body = file.readText(Charsets.UTF_8)
        val root = json.parseToJsonElement(body).jsonObject

        val summaries = linkedMapOf<MethodRef, MethodSummary>()
        val methodsArray = root["methods"]?.jsonArray.orEmpty()
        for (entry in methodsArray) {
            val obj = entry.jsonObject
            val id = obj["id"]?.jsonPrimitive?.content ?: continue
            val ref = MethodRef.fromIdOrNull(id) ?: continue
            val reads =
                obj["reads"]?.jsonArray.orEmpty()
                    .mapNotNull { it.jsonPrimitive.content }
                    .toSet()
            val writes =
                obj["writes"]?.jsonArray.orEmpty()
                    .mapNotNull { it.jsonPrimitive.content }
                    .toSet()

            val stores =
                obj["stores"]?.jsonArray.orEmpty()
                    .mapNotNull { storeElem ->
                        val storeObj = storeElem.jsonObject
                        val targetField = storeObj["targetField"]?.jsonPrimitive?.content ?: return@mapNotNull null
                        val value = storeObj["value"]?.jsonPrimitive?.content ?: return@mapNotNull null
                        val offset = storeObj["offset"]?.jsonPrimitive?.int
                        FieldStore(
                            targetField = targetField,
                            value = value,
                            offset = offset,
                        )
                    }

            val loads =
                obj["loads"]?.jsonArray.orEmpty()
                    .mapNotNull { loadElem ->
                        val loadObj = loadElem.jsonObject
                        val target = loadObj["target"]?.jsonPrimitive?.content ?: return@mapNotNull null
                        val sourceField = loadObj["sourceField"]?.jsonPrimitive?.content ?: return@mapNotNull null
                        val offset = loadObj["offset"]?.jsonPrimitive?.int
                        FieldLoad(
                            target = target,
                            sourceField = sourceField,
                            offset = offset,
                        )
                    }

            val calls =
                obj["calls"]?.jsonArray.orEmpty()
                    .mapNotNull { callElem ->
                        val callObj = callElem.jsonObject
                        val args =
                            callObj["args"]?.jsonArray.orEmpty()
                                .map { it.jsonPrimitive.contentOrNull ?: "UNKNOWN" }
                        CallSiteSummary(
                            calleeId = callObj["calleeId"]?.jsonPrimitive?.contentOrNull,
                            callOffset = callObj["callOffset"]?.jsonPrimitive?.int,
                            receiver = callObj["receiver"]?.jsonPrimitive?.contentOrNull,
                            args = args,
                            result = callObj["result"]?.jsonPrimitive?.contentOrNull,
                        )
                    }

            summaries[ref] =
                MethodSummary(
                    fieldsRead = reads,
                    fieldsWritten = writes,
                    stores = stores,
                    loads = loads,
                    calls = calls,
                )
        }

        val statsObj = root["stats"]?.jsonObject
        val stats =
            if (statsObj != null) {
                val methodsWithFieldAccess =
                    summaries.values.count { it.fieldsRead.isNotEmpty() || it.fieldsWritten.isNotEmpty() || it.stores.isNotEmpty() || it.loads.isNotEmpty() }
                val distinctFields =
                    summaries.values
                        .flatMap { it.fieldsRead + it.fieldsWritten + it.stores.map { s -> s.targetField } + it.loads.map { l -> l.sourceField } }
                        .toSet()
                MethodSummaryStats(
                    methodsIndexed = statsObj["methodsIndexed"]?.jsonPrimitive?.int ?: 0,
                    methodsWithFieldAccess = statsObj["methodsWithFieldAccess"]?.jsonPrimitive?.int ?: methodsWithFieldAccess,
                    fieldReads = statsObj["fieldReads"]?.jsonPrimitive?.int ?: summaries.values.sumOf { it.fieldsRead.size },
                    fieldWrites = statsObj["fieldWrites"]?.jsonPrimitive?.int ?: summaries.values.sumOf { it.fieldsWritten.size },
                    distinctFields = statsObj["distinctFields"]?.jsonPrimitive?.int ?: distinctFields.size,
                )
            } else {
                val distinctFields =
                    summaries.values
                        .asSequence()
                        .flatMap { s ->
                            sequenceOf(
                                s.fieldsRead.asSequence(),
                                s.fieldsWritten.asSequence(),
                                s.stores.asSequence().map { it.targetField },
                                s.loads.asSequence().map { it.sourceField },
                            ).flatten()
                        }
                        .toSet()
                MethodSummaryStats(
                    methodsIndexed = summaries.size,
                    methodsWithFieldAccess = summaries.values.count { it.fieldsRead.isNotEmpty() || it.fieldsWritten.isNotEmpty() || it.stores.isNotEmpty() || it.loads.isNotEmpty() },
                    fieldReads = summaries.values.sumOf { it.fieldsRead.size },
                    fieldWrites = summaries.values.sumOf { it.fieldsWritten.size },
                    distinctFields = distinctFields.size,
                )
            }

        return MethodSummaryIndex(summaries = summaries, stats = stats)
    }

    private fun loadFrameworkModel(): FrameworkModelIndex? {
        val file = frameworkModelCacheFile() ?: return null
        if (!file.exists()) return null

        val body = file.readText(Charsets.UTF_8)
        val root = json.parseToJsonElement(body).jsonObject

        val beans =
            root["beans"]?.jsonArray.orEmpty()
                .mapNotNull { elem ->
                    val obj = elem.jsonObject
                    val typeFqn = obj["typeFqn"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    val kindStr = obj["kind"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    val kind = runCatching { BeanKind.valueOf(kindStr) }.getOrNull() ?: return@mapNotNull null
                    val source = obj["source"]?.jsonPrimitive?.contentOrNull
                    val filePath = obj["filePath"]?.jsonPrimitive?.contentOrNull
                    val startOffset = obj["startOffset"]?.jsonPrimitive?.int
                    BeanDefinition(
                        typeFqn = typeFqn,
                        kind = kind,
                        source = source,
                        filePath = filePath,
                        startOffset = startOffset,
                    )
                }

        val injections =
            root["injections"]?.jsonArray.orEmpty()
                .mapNotNull { elem ->
                    val obj = elem.jsonObject
                    val ownerClassFqn = obj["ownerClassFqn"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    val kindStr = obj["kind"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    val kind = runCatching { InjectionKind.valueOf(kindStr) }.getOrNull() ?: return@mapNotNull null
                    val targetTypeFqn = obj["targetTypeFqn"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    val name = obj["name"]?.jsonPrimitive?.contentOrNull
                    val filePath = obj["filePath"]?.jsonPrimitive?.contentOrNull
                    val startOffset = obj["startOffset"]?.jsonPrimitive?.int
                    InjectionPoint(
                        ownerClassFqn = ownerClassFqn,
                        kind = kind,
                        targetTypeFqn = targetTypeFqn,
                        name = name,
                        filePath = filePath,
                        startOffset = startOffset,
                    )
                }

        val statsObj = root["stats"]?.jsonObject
        val stats =
            if (statsObj != null) {
                FrameworkModelStats(
                    beans = statsObj["beans"]?.jsonPrimitive?.int ?: beans.size,
                    injections = statsObj["injections"]?.jsonPrimitive?.int ?: injections.size,
                    classesWithBeans = statsObj["classesWithBeans"]?.jsonPrimitive?.int ?: 0,
                    classesWithInjections = statsObj["classesWithInjections"]?.jsonPrimitive?.int ?: 0,
                )
            } else {
                FrameworkModelStats(
                    beans = beans.size,
                    injections = injections.size,
                    classesWithBeans = 0,
                    classesWithInjections = 0,
                )
            }

        return FrameworkModelIndex(beans = beans, injections = injections, stats = stats)
    }

    private fun cacheFile(): File? {
        val basePath = project.basePath ?: return null
        return File(basePath, ".idea/secrux/callgraph.json")
    }

    private fun typeHierarchyCacheFile(): File? {
        val basePath = project.basePath ?: return null
        return File(basePath, ".idea/secrux/typehierarchy.json")
    }

    private fun methodSummaryCacheFile(): File? {
        val basePath = project.basePath ?: return null
        return File(basePath, ".idea/secrux/methodsummaries.json")
    }

    private fun frameworkModelCacheFile(): File? {
        val basePath = project.basePath ?: return null
        return File(basePath, ".idea/secrux/framework-model.json")
    }

    private fun toProjectRelativePath(absolutePath: String): String {
        val basePath = project.basePath ?: return absolutePath
        return absolutePath.removePrefix(basePath).removePrefix("/")
    }

    private fun buildGraphInternal(indicator: ProgressIndicator): BuildResult {
        val settings = SecruxProjectSettings.getInstance(project).state
        val excludedPathRegex =
            settings.excludedPathRegex.trim().takeIf { it.isNotEmpty() }?.let { pattern ->
                runCatching { Regex(pattern) }
                    .onFailure { e -> log.warn("Invalid excludedPathRegex: $pattern", e) }
                    .getOrNull()
            }

        val scope = GlobalSearchScope.projectScope(project)
        val psiManager = PsiManager.getInstance(project)
        val fileIndex = ProjectFileIndex.getInstance(project)

        val sourceFiles =
            ReadAction.compute<List<VirtualFile>, RuntimeException> {
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
        val callSites = linkedMapOf<CallEdge, CallSiteLocation>()
        val edgeKinds = linkedMapOf<CallEdge, CallEdgeKind>()
        val entryPoints = linkedSetOf<MethodRef>()
        val typesIndexed = linkedSetOf<String>()
        val typeEdges = linkedSetOf<TypeHierarchyEdge>()
        val aliasByMethod = linkedMapOf<MethodRef, UnionFind>()
        val rawFieldAccessByMethod = linkedMapOf<MethodRef, MutableSet<RawFieldAccess>>()
        val rawFieldStoresByMethod = linkedMapOf<MethodRef, MutableList<RawFieldStore>>()
        val rawFieldLoadsByMethod = linkedMapOf<MethodRef, MutableList<RawFieldLoad>>()
        val rawCallSitesByMethod = linkedMapOf<MethodRef, MutableList<RawCallSite>>()
        val beans = linkedSetOf<BeanDefinition>()
        val injections = mutableListOf<InjectionPoint>()
        val classesWithBeans = linkedSetOf<String>()
        val classesWithInjections = linkedSetOf<String>()

        var unresolvedCalls = 0
        var edgeCount = 0

        val overrideTargetsCache = hashMapOf<PsiMethod, List<MethodRef>>()

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
                        override fun visitClass(node: UClass): Boolean {
                            val clazz = node.javaPsi
                            val child = clazz.qualifiedName ?: return false
                            typesIndexed.add(child)

                            for (t in clazz.extendsListTypes) {
                                val parent = t.resolve()?.qualifiedName ?: continue
                                if (parent == "java.lang.Object") continue
                                typesIndexed.add(parent)
                                typeEdges.add(TypeHierarchyEdge(from = child, to = parent, kind = TypeEdgeKind.EXTE))
                            }
                            for (t in clazz.implementsListTypes) {
                                val parent = t.resolve()?.qualifiedName ?: continue
                                typesIndexed.add(parent)
                                typeEdges.add(TypeHierarchyEdge(from = child, to = parent, kind = TypeEdgeKind.IMPL))
                            }

                            val relPath = toProjectRelativePath(file.path)

                            if (clazz.hasAnyAnnotation(SPRING_BEAN_STEREOTYPE_ANNOTATIONS)) {
                                classesWithBeans.add(child)
                                val offset =
                                    clazz.nameIdentifier?.textRange?.startOffset
                                        ?: node.uastAnchor?.sourcePsi?.textRange?.startOffset
                                        ?: node.sourcePsi?.textRange?.startOffset
                                beans.add(
                                    BeanDefinition(
                                        typeFqn = child,
                                        kind = BeanKind.STEREOTYPE,
                                        source = "stereotype",
                                        filePath = relPath,
                                        startOffset = offset,
                                    )
                                )
                            }

                            fun normalizeType(type: com.intellij.psi.PsiType?): String? {
                                if (type == null) return null
                                val canonical = type.canonicalText.substringBefore("<").trim()
                                if (canonical.isBlank()) return null
                                if (canonical == "void") return null
                                return canonical
                            }

                            for (method in clazz.methods) {
                                if (!method.hasAnyAnnotation(SPRING_BEAN_METHOD_ANNOTATIONS)) continue
                                val returnTypeFqn = normalizeType(method.returnType) ?: continue
                                classesWithBeans.add(child)
                                beans.add(
                                    BeanDefinition(
                                        typeFqn = returnTypeFqn,
                                        kind = BeanKind.BEAN_METHOD,
                                        source = method.toMethodRefOrNull()?.id ?: method.name,
                                        filePath = relPath,
                                        startOffset = method.nameIdentifier?.textRange?.startOffset ?: method.textRange?.startOffset,
                                    )
                                )
                            }

                            for (field in clazz.fields) {
                                if (!field.hasAnyAnnotation(SPRING_INJECTION_ANNOTATIONS)) continue
                                val targetTypeFqn = normalizeType(field.type) ?: continue
                                classesWithInjections.add(child)
                                injections.add(
                                    InjectionPoint(
                                        ownerClassFqn = child,
                                        kind = InjectionKind.FIELD,
                                        targetTypeFqn = targetTypeFqn,
                                        name = field.name,
                                        filePath = relPath,
                                        startOffset = field.nameIdentifier?.textRange?.startOffset ?: field.textRange?.startOffset,
                                    )
                                )
                            }

                            for (ctor in clazz.constructors) {
                                if (!ctor.hasAnyAnnotation(SPRING_INJECTION_ANNOTATIONS)) continue
                                for (param in ctor.parameterList.parameters) {
                                    val targetTypeFqn = normalizeType(param.type) ?: continue
                                    classesWithInjections.add(child)
                                    injections.add(
                                        InjectionPoint(
                                            ownerClassFqn = child,
                                            kind = InjectionKind.CONSTRUCTOR_PARAM,
                                            targetTypeFqn = targetTypeFqn,
                                            name = param.name,
                                            filePath = relPath,
                                            startOffset = param.textRange?.startOffset,
                                        )
                                    )
                                }
                            }

                            for (method in clazz.methods) {
                                if (method.isConstructor) continue
                                if (!method.hasAnyAnnotation(SPRING_INJECTION_ANNOTATIONS)) continue
                                for (param in method.parameterList.parameters) {
                                    val targetTypeFqn = normalizeType(param.type) ?: continue
                                    classesWithInjections.add(child)
                                    injections.add(
                                        InjectionPoint(
                                            ownerClassFqn = child,
                                            kind = InjectionKind.METHOD_PARAM,
                                            targetTypeFqn = targetTypeFqn,
                                            name = param.name,
                                            filePath = relPath,
                                            startOffset = param.textRange?.startOffset,
                                        )
                                    )
                                }
                            }

                            return false
                        }

                        override fun visitMethod(node: UMethod): Boolean {
                            val ref = node.javaPsi.toMethodRefOrNull() ?: return false
                            val offset =
                                node.javaPsi.nameIdentifier?.textRange?.startOffset
                                    ?: node.uastAnchor?.sourcePsi?.textRange?.startOffset
                                    ?: node.sourcePsi?.textRange?.startOffset
                                    ?: 0
                            methods.putIfAbsent(ref, MethodLocation(file = file, startOffset = offset))
                            outgoing.putIfAbsent(ref, linkedSetOf())
                            incoming.putIfAbsent(ref, linkedSetOf())
                            if (node.javaPsi.isRecognizedEntryPoint()) {
                                entryPoints.add(ref)
                            }
                            return false
                        }

                        override fun visitCallExpression(node: UCallExpression): Boolean {
                            val callerMethod = findEnclosingMethod(node) ?: return false
                            val callerRef = callerMethod.javaPsi.toMethodRefOrNull() ?: return false

                            val callOffset =
                                node.sourcePsi?.textRange?.startOffset
                                    ?: node.methodIdentifier?.sourcePsi?.textRange?.startOffset

                            val receiverToken =
                                node.receiver?.let { receiverExpr ->
                                    valueTokenForExpression(callerMethod, callerRef, receiverExpr)
                                }

                            val argTokens =
                                node.valueArguments.map { arg ->
                                    valueTokenForExpression(callerMethod, callerRef, arg)
                                }

                            val resultToken = resolveCallResultToken(callerMethod, callerRef, node)

                            val calleePsi = node.resolve() as? PsiMethod
                            val calleeRef = calleePsi?.toMethodRefOrNull()
                            if (calleeRef == null) {
                                unresolvedCalls++
                                rawCallSitesByMethod.getOrPut(callerRef) { mutableListOf() }.add(
                                    RawCallSite(
                                        calleeId = null,
                                        callOffset = callOffset,
                                        receiverToken = receiverToken,
                                        argTokens = argTokens,
                                        resultToken = resultToken,
                                    )
                                )
                                return false
                            }

                            rawCallSitesByMethod.getOrPut(callerRef) { mutableListOf() }.add(
                                RawCallSite(
                                    calleeId = calleeRef.id,
                                    callOffset = callOffset,
                                    receiverToken = receiverToken,
                                    argTokens = argTokens,
                                    resultToken = resultToken,
                                )
                            )

                            fun addEdge(calleeRef: MethodRef, kind: CallEdgeKind) {
                                val edge = CallEdge(caller = callerRef, callee = calleeRef)
                                edgeKinds.putIfAbsent(edge, kind)
                                if (outgoing.getOrPut(callerRef) { linkedSetOf() }.add(calleeRef)) {
                                    edgeCount++
                                }
                                incoming.getOrPut(calleeRef) { linkedSetOf() }.add(callerRef)

                                if (callOffset != null && callOffset >= 0) {
                                    callSites.putIfAbsent(
                                        edge,
                                        CallSiteLocation(file = file, startOffset = callOffset),
                                    )
                                }
                            }

                            addEdge(calleeRef, CallEdgeKind.CALL)

                            // IoC/DI style code often calls interface/abstract methods; approximate dispatch by wiring possible overrides.
                            if (calleePsi?.shouldExpandToOverrides() == true) {
                                val dispatchKind =
                                    if (calleePsi.containingClass?.isInterface == true) {
                                        CallEdgeKind.IMPL
                                    } else {
                                        CallEdgeKind.EXTE
                                    }
                                val targets =
                                    overrideTargetsCache.getOrPut(calleePsi) {
                                        computeOverrideTargets(
                                            method = calleePsi,
                                            scope = scope,
                                            fileIndex = fileIndex,
                                            excludedPathRegex = excludedPathRegex,
                                        )
                                    }
                                for (overrideRef in targets) {
                                    addEdge(overrideRef, dispatchKind)
                                }
                            }

                            return false
                        }

                        private fun resolveVariableToken(enclosingMethod: UMethod, element: UElement?): String? {
                            val simple = element as? USimpleNameReferenceExpression ?: return null
                            val resolved = simple.resolve() as? PsiVariable ?: return null
                            if (resolved is PsiField) return null
                            return tokenForPsiVariable(enclosingMethod, resolved)
                        }

                        private fun tokenForPsiVariable(enclosingMethod: UMethod, variable: PsiVariable): String? {
                            val name = variable.name?.takeIf { it.isNotBlank() } ?: return null
                            if (variable is com.intellij.psi.PsiParameter) {
                                val byIdentity =
                                    enclosingMethod.javaPsi.parameterList.parameters
                                        .indexOfFirst { it == variable }
                                val byName =
                                    if (byIdentity >= 0) byIdentity else
                                        enclosingMethod.javaPsi.parameterList.parameters
                                            .indexOfFirst { it.name == name }
                                return if (byName >= 0) {
                                    "PARAM:$byName"
                                } else {
                                    "PARAM:$name"
                                }
                            }
                            return "LOCAL:$name"
                        }

                        private fun recordLocalAlias(methodRef: MethodRef, left: String, right: String) {
                            aliasByMethod.getOrPut(methodRef) { UnionFind() }.union(left, right)
                        }

                        private fun receiverTokenForQualified(enclosingMethod: UMethod, node: UQualifiedReferenceExpression): String? {
                            val receiver = node.receiver ?: return null
                            return when (receiver) {
                                is UThisExpression -> THIS_RECEIVER_TOKEN
                                is USimpleNameReferenceExpression -> resolveVariableToken(enclosingMethod, receiver) ?: OTHER_RECEIVER_TOKEN
                                else -> OTHER_RECEIVER_TOKEN
                            }
                        }

                        private fun computeThisOrStaticFieldKey(
                            methodRef: MethodRef,
                            ownerFqn: String,
                            fieldName: String,
                            isStatic: Boolean,
                            receiverToken: String?
                        ): String? {
                            if (isStatic) {
                                return fieldKey(base = "STATIC", ownerFqn = ownerFqn, fieldName = fieldName)
                            }

                            val uf = aliasByMethod[methodRef]
                            return when {
                                receiverToken == null || receiverToken == THIS_RECEIVER_TOKEN ->
                                    fieldKey(base = "THIS", ownerFqn = ownerFqn, fieldName = fieldName)

                                receiverToken == OTHER_RECEIVER_TOKEN ->
                                    null

                                uf != null && uf.connected(receiverToken, THIS_RECEIVER_TOKEN) ->
                                    fieldKey(base = "THIS", ownerFqn = ownerFqn, fieldName = fieldName)

                                else -> null
                            }
                        }

                        private fun valueTokenForExpression(
                            enclosingMethod: UMethod,
                            methodRef: MethodRef,
                            expr: UElement?
                        ): String? {
                            val element = expr ?: return null
                            return when (element) {
                                is UThisExpression -> THIS_RECEIVER_TOKEN
                                is USimpleNameReferenceExpression -> {
                                    val resolved = element.resolve()
                                    when (resolved) {
                                        is PsiField -> {
                                            val owner = resolved.containingClass?.qualifiedName ?: return null
                                            computeThisOrStaticFieldKey(
                                                methodRef = methodRef,
                                                ownerFqn = owner,
                                                fieldName = resolved.name,
                                                isStatic = resolved.hasModifierProperty(PsiModifier.STATIC),
                                                receiverToken = null,
                                            )
                                        }

                                        is PsiVariable -> tokenForPsiVariable(enclosingMethod, resolved)

                                        else -> null
                                    }
                                }

                                is UQualifiedReferenceExpression -> {
                                    val resolved = element.resolve() as? PsiField ?: return null
                                    val owner = resolved.containingClass?.qualifiedName ?: return null
                                    val receiverToken =
                                        if (resolved.hasModifierProperty(PsiModifier.STATIC)) {
                                            null
                                        } else {
                                            receiverTokenForQualified(enclosingMethod, element)
                                        }
                                    computeThisOrStaticFieldKey(
                                        methodRef = methodRef,
                                        ownerFqn = owner,
                                        fieldName = resolved.name,
                                        isStatic = resolved.hasModifierProperty(PsiModifier.STATIC),
                                        receiverToken = receiverToken,
                                    )
                                }

                                else -> null
                            }
                        }

                        private fun resolveCallResultToken(
                            enclosingMethod: UMethod,
                            callerRef: MethodRef,
                            node: UCallExpression,
                        ): String? {
                            var current: UElement = node
                            var parent: UElement? = node.uastParent
                            while (parent is UParenthesizedExpression) {
                                current = parent
                                parent = parent.uastParent
                            }

                            when (parent) {
                                is UBinaryExpression -> {
                                    if (parent.operator == UastBinaryOperator.ASSIGN && isAssignmentRightOperand(parent, current)) {
                                        return valueTokenForExpression(enclosingMethod, callerRef, parent.leftOperand)
                                    }
                                }

                                is UVariable -> {
                                    val initRange = parent.uastInitializer?.sourcePsi?.textRange
                                    val currentRange = current.sourcePsi?.textRange
                                    if (parent.uastInitializer == current || (initRange != null && currentRange != null && initRange == currentRange)) {
                                        val name = parent.name?.takeIf { it.isNotBlank() } ?: return null
                                        return "LOCAL:$name"
                                    }
                                }

                                is UReturnExpression -> {
                                    val retRange = parent.returnExpression?.sourcePsi?.textRange
                                    val currentRange = current.sourcePsi?.textRange
                                    if (parent.returnExpression == current || (retRange != null && currentRange != null && retRange == currentRange)) {
                                        return RETURN_VALUE_TOKEN
                                    }
                                }
                            }

                            return null
                        }

                        private fun recordFieldAccess(
                            node: UElement,
                            field: PsiField,
                            receiverToken: String?,
                            isWrite: Boolean
                        ) {
                            val owner = field.containingClass?.qualifiedName ?: return
                            val enclosing = findEnclosingMethod(node) ?: return
                            val ref = enclosing.javaPsi.toMethodRefOrNull() ?: return

                            val access =
                                RawFieldAccess(
                                    ownerFqn = owner,
                                    fieldName = field.name,
                                    isStatic = field.hasModifierProperty(PsiModifier.STATIC),
                                    receiverToken = receiverToken,
                                    isWrite = isWrite,
                                )
                            rawFieldAccessByMethod.getOrPut(ref) { linkedSetOf() }.add(access)
                        }

                        private fun resolveField(element: UElement?): PsiField? {
                            if (element == null) return null
                            val resolved =
                                when (element) {
                                    is UQualifiedReferenceExpression -> element.resolve()
                                    is USimpleNameReferenceExpression -> element.resolve()
                                    else -> null
                                }
                            return resolved as? PsiField
                        }

                        private fun isAssignmentLeftOperand(element: UElement): Boolean {
                            val parent = element.uastParent
                            if (parent is UBinaryExpression && parent.operator == UastBinaryOperator.ASSIGN) {
                                val lhsRange = parent.leftOperand.sourcePsi?.textRange
                                val elementRange = element.sourcePsi?.textRange
                                if (lhsRange != null && elementRange != null && lhsRange == elementRange) return true
                                if (parent.leftOperand == element) return true
                            }
                            return false
                        }

                        private fun isAssignmentRightOperand(parent: UBinaryExpression, element: UElement): Boolean {
                            if (parent.operator != UastBinaryOperator.ASSIGN) return false
                            val rhsRange = parent.rightOperand.sourcePsi?.textRange
                            val elementRange = element.sourcePsi?.textRange
                            if (rhsRange != null && elementRange != null && rhsRange == elementRange) return true
                            if (parent.rightOperand == element) return true
                            return false
                        }

                        override fun visitBinaryExpression(node: UBinaryExpression): Boolean {
                            if (node.operator != UastBinaryOperator.ASSIGN) return false
                            val enclosing = findEnclosingMethod(node) ?: return false
                            val methodRef = enclosing.javaPsi.toMethodRefOrNull() ?: return false

                            val leftVar = resolveVariableToken(enclosing, node.leftOperand)
                            val rightVar =
                                when (val rhs = node.rightOperand) {
                                    is UThisExpression -> THIS_RECEIVER_TOKEN
                                    is USimpleNameReferenceExpression -> resolveVariableToken(enclosing, rhs)
                                    else -> null
                                }
                            if (leftVar != null && rightVar != null) {
                                recordLocalAlias(methodRef, left = leftVar, right = rightVar)
                            }

                            val assignOffset = node.sourcePsi?.textRange?.startOffset

                            val lhsField = resolveField(node.leftOperand)
                            if (lhsField != null) {
                                val receiverToken =
                                    if (lhsField.hasModifierProperty(PsiModifier.STATIC)) {
                                        null
                                    } else {
                                        when (val lhs = node.leftOperand) {
                                            is UQualifiedReferenceExpression -> receiverTokenForQualified(enclosing, lhs)
                                            is USimpleNameReferenceExpression -> null
                                            else -> OTHER_RECEIVER_TOKEN
                                        }
                                    }
                                recordFieldAccess(node, lhsField, receiverToken = receiverToken, isWrite = true)

                                val owner = lhsField.containingClass?.qualifiedName
                                if (owner != null) {
                                    rawFieldStoresByMethod.getOrPut(methodRef) { mutableListOf() }.add(
                                        RawFieldStore(
                                            ownerFqn = owner,
                                            fieldName = lhsField.name,
                                            isStatic = lhsField.hasModifierProperty(PsiModifier.STATIC),
                                            receiverToken = receiverToken,
                                            valueToken = valueTokenForExpression(enclosing, methodRef, node.rightOperand),
                                            offset = assignOffset,
                                        )
                                    )
                                }
                            }

                            val rhsField = resolveField(node.rightOperand)
                            if (rhsField != null) {
                                val receiverToken =
                                    if (rhsField.hasModifierProperty(PsiModifier.STATIC)) {
                                        null
                                    } else {
                                        when (val rhs = node.rightOperand) {
                                            is UQualifiedReferenceExpression -> receiverTokenForQualified(enclosing, rhs)
                                            is USimpleNameReferenceExpression -> null
                                            else -> OTHER_RECEIVER_TOKEN
                                        }
                                    }

                                val owner = rhsField.containingClass?.qualifiedName
                                if (owner != null) {
                                    rawFieldLoadsByMethod.getOrPut(methodRef) { mutableListOf() }.add(
                                        RawFieldLoad(
                                            ownerFqn = owner,
                                            fieldName = rhsField.name,
                                            isStatic = rhsField.hasModifierProperty(PsiModifier.STATIC),
                                            receiverToken = receiverToken,
                                            targetToken = valueTokenForExpression(enclosing, methodRef, node.leftOperand),
                                            offset = assignOffset,
                                        )
                                    )
                                }
                            }
                            return false
                        }

                        override fun visitReturnExpression(node: UReturnExpression): Boolean {
                            val enclosing = findEnclosingMethod(node) ?: return false
                            val methodRef = enclosing.javaPsi.toMethodRefOrNull() ?: return false

                            val returned = node.returnExpression ?: return false
                            val field = resolveField(returned) ?: return false

                            val receiverToken =
                                if (field.hasModifierProperty(PsiModifier.STATIC)) {
                                    null
                                } else {
                                    when (returned) {
                                        is UQualifiedReferenceExpression -> receiverTokenForQualified(enclosing, returned)
                                        is USimpleNameReferenceExpression -> null
                                        else -> OTHER_RECEIVER_TOKEN
                                    }
                                }

                            val owner = field.containingClass?.qualifiedName ?: return false
                            val offset = node.sourcePsi?.textRange?.startOffset ?: returned.sourcePsi?.textRange?.startOffset

                            rawFieldLoadsByMethod.getOrPut(methodRef) { mutableListOf() }.add(
                                RawFieldLoad(
                                    ownerFqn = owner,
                                    fieldName = field.name,
                                    isStatic = field.hasModifierProperty(PsiModifier.STATIC),
                                    receiverToken = receiverToken,
                                    targetToken = RETURN_VALUE_TOKEN,
                                    offset = offset,
                                )
                            )

                            return false
                        }

                        override fun visitQualifiedReferenceExpression(node: UQualifiedReferenceExpression): Boolean {
                            if (isAssignmentLeftOperand(node)) return false
                            val field = resolveField(node) ?: return false
                            val enclosing = findEnclosingMethod(node) ?: return false
                            val receiverToken =
                                if (field.hasModifierProperty(PsiModifier.STATIC)) {
                                    null
                                } else {
                                    receiverTokenForQualified(enclosing, node)
                                }
                            recordFieldAccess(node, field, receiverToken = receiverToken, isWrite = false)
                            return false
                        }

                        override fun visitSimpleNameReferenceExpression(node: USimpleNameReferenceExpression): Boolean {
                            if (isAssignmentLeftOperand(node)) return false
                            if (node.uastParent is UQualifiedReferenceExpression) return false
                            val field = resolveField(node) ?: return false
                            val receiverToken =
                                if (field.hasModifierProperty(PsiModifier.STATIC)) null else null
                            recordFieldAccess(node, field, receiverToken = receiverToken, isWrite = false)
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

        val typeHierarchy =
            TypeHierarchyIndex(
                edges = typeEdges.toSet(),
                stats =
                    TypeHierarchyStats(
                        typesIndexed = typesIndexed.size,
                        edges = typeEdges.size,
                        implEdges = typeEdges.count { it.kind == TypeEdgeKind.IMPL },
                        exteEdges = typeEdges.count { it.kind == TypeEdgeKind.EXTE },
                    ),
            )

        val summaries = linkedMapOf<MethodRef, MethodSummary>()
        val distinctFields = linkedSetOf<String>()
        var methodsWithFieldAccess = 0

        fun computeThisOrStaticFieldKey(
            methodRef: MethodRef,
            ownerFqn: String,
            fieldName: String,
            isStatic: Boolean,
            receiverToken: String?,
        ): String? {
            if (isStatic) {
                return fieldKey(
                    base = "STATIC",
                    ownerFqn = ownerFqn,
                    fieldName = fieldName,
                )
            }

            val uf = aliasByMethod[methodRef]
            return when {
                receiverToken == null || receiverToken == THIS_RECEIVER_TOKEN ->
                    fieldKey(
                        base = "THIS",
                        ownerFqn = ownerFqn,
                        fieldName = fieldName,
                    )

                receiverToken == OTHER_RECEIVER_TOKEN -> null

                uf != null && uf.connected(receiverToken, THIS_RECEIVER_TOKEN) ->
                    fieldKey(
                        base = "THIS",
                        ownerFqn = ownerFqn,
                        fieldName = fieldName,
                    )

                else -> null
            }
        }

        fun externalizeValueToken(uf: UnionFind?, token: String?): String? {
            if (token == null) return null
            return when {
                token == THIS_RECEIVER_TOKEN -> "THIS"
                token == RETURN_VALUE_TOKEN -> "RET"
                token == OTHER_RECEIVER_TOKEN -> "UNKNOWN"
                uf != null && (token.startsWith("LOCAL:") || token.startsWith("PARAM:")) && uf.connected(token, THIS_RECEIVER_TOKEN) -> "THIS"
                else -> token
            }
        }

        val methodsToSummarize =
            linkedSetOf<MethodRef>().apply {
                addAll(rawFieldAccessByMethod.keys)
                addAll(rawFieldStoresByMethod.keys)
                addAll(rawFieldLoadsByMethod.keys)
                addAll(rawCallSitesByMethod.keys)
            }

        for (methodRef in methodsToSummarize) {
            val uf = aliasByMethod[methodRef]
            val reads = linkedSetOf<String>()
            val writes = linkedSetOf<String>()

            val rawAccesses = rawFieldAccessByMethod[methodRef].orEmpty()
            for (access in rawAccesses) {
                val key =
                    computeThisOrStaticFieldKey(
                        methodRef = methodRef,
                        ownerFqn = access.ownerFqn,
                        fieldName = access.fieldName,
                        isStatic = access.isStatic,
                        receiverToken = access.receiverToken,
                    ) ?: continue

                if (access.isWrite) {
                    writes.add(key)
                } else {
                    reads.add(key)
                }
            }

            val stores =
                rawFieldStoresByMethod[methodRef].orEmpty()
                    .mapNotNull { raw ->
                        val key =
                            computeThisOrStaticFieldKey(
                                methodRef = methodRef,
                                ownerFqn = raw.ownerFqn,
                                fieldName = raw.fieldName,
                                isStatic = raw.isStatic,
                                receiverToken = raw.receiverToken,
                            ) ?: return@mapNotNull null
                        distinctFields.add(key)
                        FieldStore(
                            targetField = key,
                            value = externalizeValueToken(uf, raw.valueToken) ?: "UNKNOWN",
                            offset = raw.offset,
                        )
                    }

            val loads =
                rawFieldLoadsByMethod[methodRef].orEmpty()
                    .mapNotNull { raw ->
                        val key =
                            computeThisOrStaticFieldKey(
                                methodRef = methodRef,
                                ownerFqn = raw.ownerFqn,
                                fieldName = raw.fieldName,
                                isStatic = raw.isStatic,
                                receiverToken = raw.receiverToken,
                            ) ?: return@mapNotNull null
                        val target = externalizeValueToken(uf, raw.targetToken) ?: return@mapNotNull null
                        distinctFields.add(key)
                        FieldLoad(
                            target = target,
                            sourceField = key,
                            offset = raw.offset,
                        )
                    }

            val calls =
                rawCallSitesByMethod[methodRef].orEmpty()
                    .map { raw ->
                        CallSiteSummary(
                            calleeId = raw.calleeId,
                            callOffset = raw.callOffset,
                            receiver = raw.receiverToken?.let { externalizeValueToken(uf, it) ?: "UNKNOWN" },
                            args = raw.argTokens.map { externalizeValueToken(uf, it) ?: "UNKNOWN" },
                            result = raw.resultToken?.let { externalizeValueToken(uf, it) ?: "UNKNOWN" },
                        )
                    }

            if (reads.isEmpty() && writes.isEmpty() && stores.isEmpty() && loads.isEmpty() && calls.isEmpty()) continue
            if (reads.isNotEmpty() || writes.isNotEmpty()) methodsWithFieldAccess++

            distinctFields.addAll(reads)
            distinctFields.addAll(writes)
            summaries[methodRef] =
                MethodSummary(
                    fieldsRead = reads,
                    fieldsWritten = writes,
                    stores = stores,
                    loads = loads,
                    calls = calls,
                )
        }

        val methodSummaries =
            MethodSummaryIndex(
                summaries = summaries.toMap(),
                stats =
                    MethodSummaryStats(
                        methodsIndexed = methods.size,
                        methodsWithFieldAccess = methodsWithFieldAccess,
                        fieldReads = summaries.values.sumOf { it.fieldsRead.size },
                        fieldWrites = summaries.values.sumOf { it.fieldsWritten.size },
                        distinctFields = distinctFields.size,
                    ),
            )

        val frameworkModel =
            FrameworkModelIndex(
                beans = beans.toList(),
                injections = injections.toList(),
                stats =
                    FrameworkModelStats(
                        beans = beans.size,
                        injections = injections.size,
                        classesWithBeans = classesWithBeans.size,
                        classesWithInjections = classesWithInjections.size,
                    ),
            )

        return BuildResult(
            graph =
                CallGraph(
                    methods = methods.toMap(),
                    outgoing = outgoing.mapValues { (_, v) -> v.toSet() },
                    incoming = incoming.mapValues { (_, v) -> v.toSet() },
                    callSites = callSites.toMap(),
                    edgeKinds = edgeKinds.toMap(),
                    entryPoints = entryPoints.toSet(),
                    stats = stats,
                ),
            typeHierarchy = typeHierarchy,
            methodSummaries = methodSummaries,
            frameworkModel = frameworkModel,
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

    private fun PsiMethod.isRecognizedEntryPoint(): Boolean {
        return isSpringRequestHandler() ||
            isJaxRsRequestHandler() ||
            isMicronautRequestHandler() ||
            isQuarkusRouteHandler() ||
            isServletEntryPoint() ||
            isWebSocketEndpointHandler() ||
            isGrpcServiceMethod() ||
            isDubboServiceMethod() ||
            isMainMethodEntryPoint()
    }

    private fun PsiMethod.isSpringRequestHandler(): Boolean {
        val clazz = containingClass ?: return false
        if (!clazz.hasAnyAnnotation(SPRING_CONTROLLER_ANNOTATIONS)) return false
        if (!hasAnyAnnotation(SPRING_MAPPING_ANNOTATIONS)) return false
        return true
    }

    private fun PsiMethod.isSpringIoCEntryPoint(): Boolean {
        val clazz = containingClass ?: return false
        if (hasAnyAnnotation(SPRING_IOC_METHOD_ANNOTATIONS)) return true

        if (name == "run" && clazz.isInheritorOfAny(SPRING_RUNNER_INTERFACES)) return true
        if (name == "onApplicationEvent" && clazz.isInheritorOfAny(SPRING_APPLICATION_LISTENER_INTERFACES)) return true

        return false
    }

    private fun PsiMethod.isJaxRsRequestHandler(): Boolean {
        val clazz = containingClass ?: return false

        val classHasPath = clazz.hasAnyAnnotation(JAXRS_PATH_ANNOTATIONS)
        val methodHasPath = hasAnyAnnotation(JAXRS_PATH_ANNOTATIONS)
        if (!classHasPath && !methodHasPath) return false

        val hasHttpMethod = hasAnyAnnotation(JAXRS_HTTP_METHOD_ANNOTATIONS) || hasAnyMetaAnnotation(JAXRS_HTTP_METHOD_META_ANNOTATIONS)
        if (hasHttpMethod) return true

        // Sub-resource locator: @Path without @GET/@POST... on method.
        return methodHasPath
    }

    private fun PsiMethod.isMicronautRequestHandler(): Boolean {
        val clazz = containingClass ?: return false
        if (!clazz.hasAnyAnnotation(MICRONAUT_CONTROLLER_ANNOTATIONS)) return false
        if (!hasAnyAnnotation(MICRONAUT_MAPPING_ANNOTATIONS)) return false
        return true
    }

    private fun PsiMethod.isQuarkusRouteHandler(): Boolean {
        if (!hasAnyAnnotation(QUARKUS_ROUTE_ANNOTATIONS)) return false
        return true
    }

    private fun PsiMethod.isServletEntryPoint(): Boolean {
        val clazz = containingClass ?: return false
        val name = name

        if (HTTP_SERVLET_METHOD_NAMES.contains(name) && clazz.isInheritorOfAny(HTTP_SERVLET_CLASS_FQNS)) {
            return true
        }
        if (name == "doFilter" && parameterList.parametersCount == 3 && clazz.isInheritorOfAny(SERVLET_FILTER_CLASS_FQNS)) {
            return true
        }
        if (name == "service" && clazz.isInheritorOfAny(SERVLET_CLASS_FQNS)) {
            return true
        }
        return false
    }

    private fun PsiMethod.isWebSocketEndpointHandler(): Boolean {
        val clazz = containingClass ?: return false
        if (!clazz.hasAnyAnnotation(WEBSOCKET_ENDPOINT_ANNOTATIONS)) return false
        if (!hasAnyAnnotation(WEBSOCKET_HANDLER_ANNOTATIONS)) return false
        return true
    }

    private fun PsiMethod.isGrpcServiceMethod(): Boolean {
        val clazz = containingClass ?: return false
        if (!clazz.isInheritorOfAny(GRPC_SERVICE_CLASS_FQNS)) return false
        if (isConstructor) return false
        if (name == "bindService") return false
        if (!hasModifierProperty(PsiModifier.PUBLIC) || hasModifierProperty(PsiModifier.STATIC)) return false

        if (returnType?.isStreamObserverType() == true) return true
        return parameterList.parameters.any { p -> p.type.isStreamObserverType() }
    }

    private fun PsiMethod.isDubboServiceMethod(): Boolean {
        val clazz = containingClass ?: return false
        val hasDubboServiceAnnotation =
            clazz.hasAnyAnnotation(DUBBO_SERVICE_UNAMBIGUOUS_ANNOTATIONS) ||
                clazz.hasAnyAnnotationExact(DUBBO_SERVICE_EXACT_ONLY_ANNOTATIONS)
        if (!hasDubboServiceAnnotation) return false
        if (isConstructor) return false
        if (!hasModifierProperty(PsiModifier.PUBLIC) || hasModifierProperty(PsiModifier.STATIC)) return false
        if (name == "\$invoke") return true
        return findSuperMethods().isNotEmpty()
    }

    private fun PsiMethod.isMainMethodEntryPoint(): Boolean {
        if (name != "main") return false
        if (!hasModifierProperty(PsiModifier.PUBLIC) || !hasModifierProperty(PsiModifier.STATIC)) return false
        val paramCount = parameterList.parametersCount
        return paramCount == 0 || paramCount == 1
    }

    private fun PsiMethod.shouldExpandToOverrides(): Boolean {
        if (hasModifierProperty(PsiModifier.STATIC) || hasModifierProperty(PsiModifier.PRIVATE) || hasModifierProperty(PsiModifier.FINAL)) return false
        val clazz = containingClass ?: return false
        if (clazz.qualifiedName?.startsWith("java.") == true) return false
        return clazz.isInterface || clazz.hasModifierProperty(PsiModifier.ABSTRACT) || hasModifierProperty(PsiModifier.ABSTRACT)
    }

    private fun computeOverrideTargets(
        method: PsiMethod,
        scope: GlobalSearchScope,
        fileIndex: ProjectFileIndex,
        excludedPathRegex: Regex?,
    ): List<MethodRef> {
        val results = mutableListOf<MethodRef>()
        val query = OverridingMethodsSearch.search(method, scope, /* checkDeep = */ true)
        for (override in query) {
            if (override.hasModifierProperty(PsiModifier.ABSTRACT)) continue
            val vf = override.containingFile?.virtualFile ?: continue
            if (!fileIndex.isInSourceContent(vf)) continue
            if (excludedPathRegex != null && excludedPathRegex.containsMatchIn(relativePath(vf.path))) continue
            val ref = override.toMethodRefOrNull() ?: continue
            results.add(ref)
            if (results.size >= MAX_DYNAMIC_DISPATCH_TARGETS) break
        }
        return results
    }

    private fun PsiClass.isInheritorOfAny(classFqns: Set<String>): Boolean {
        for (fqn in classFqns) {
            if (InheritanceUtil.isInheritor(this, fqn)) return true
        }
        return false
    }

    private fun PsiModifierListOwner.hasAnyAnnotation(annotationFqns: Set<String>): Boolean {
        val annotations = modifierList?.annotations ?: return false
        return annotations.any { ann -> ann.isAnnotationIn(annotationFqns) }
    }

    private fun PsiModifierListOwner.hasAnyAnnotationExact(annotationFqns: Set<String>): Boolean {
        val annotations = modifierList?.annotations ?: return false
        return annotations.any { ann -> ann.qualifiedName != null && ann.qualifiedName in annotationFqns }
    }

    private fun PsiModifierListOwner.hasAnyMetaAnnotation(metaAnnotationFqns: Set<String>): Boolean {
        val annotations = modifierList?.annotations ?: return false
        return annotations.any { ann -> ann.isMetaAnnotatedWithAny(metaAnnotationFqns) }
    }

    private fun PsiAnnotation.isAnnotationIn(annotationFqns: Set<String>): Boolean {
        val qName = qualifiedName
        if (qName != null) return qName in annotationFqns
        val short = nameReferenceElement?.referenceName ?: return false
        return annotationFqns.any { it.endsWith(".$short") }
    }

    private fun PsiAnnotation.isMetaAnnotatedWithAny(metaAnnotationFqns: Set<String>): Boolean {
        val annotationClass = resolveAnnotationClass() ?: return false
        return annotationClass.hasAnyAnnotation(metaAnnotationFqns)
    }

    private fun PsiAnnotation.resolveAnnotationClass(): PsiClass? {
        return nameReferenceElement?.resolve() as? PsiClass
    }

    private fun com.intellij.psi.PsiType.isStreamObserverType(): Boolean {
        val canonical = canonicalText
        return canonical == GRPC_STREAM_OBSERVER_FQN || canonical.startsWith("$GRPC_STREAM_OBSERVER_FQN<")
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

        private val SPRING_BEAN_STEREOTYPE_ANNOTATIONS =
            setOf(
                "org.springframework.stereotype.Component",
                "org.springframework.stereotype.Service",
                "org.springframework.stereotype.Repository",
                "org.springframework.stereotype.Controller",
                "org.springframework.web.bind.annotation.RestController",
                "org.springframework.context.annotation.Configuration",
                "org.springframework.boot.autoconfigure.SpringBootApplication",
                "org.springframework.boot.SpringBootConfiguration",
            )

        private val SPRING_BEAN_METHOD_ANNOTATIONS =
            setOf(
                "org.springframework.context.annotation.Bean",
            )

        private val SPRING_INJECTION_ANNOTATIONS =
            setOf(
                "org.springframework.beans.factory.annotation.Autowired",
                "org.springframework.beans.factory.annotation.Value",
                "javax.inject.Inject",
                "jakarta.inject.Inject",
                "javax.annotation.Resource",
                "jakarta.annotation.Resource",
            )

        private val SPRING_IOC_METHOD_ANNOTATIONS =
            setOf(
                // lifecycle callbacks
                "javax.annotation.PostConstruct",
                "jakarta.annotation.PostConstruct",

                // scheduling
                "org.springframework.scheduling.annotation.Scheduled",

                // events
                "org.springframework.context.event.EventListener",

                // messaging
                "org.springframework.kafka.annotation.KafkaListener",
                "org.springframework.amqp.rabbit.annotation.RabbitListener",
                "org.springframework.jms.annotation.JmsListener",
                "org.springframework.messaging.handler.annotation.MessageMapping",
                "org.springframework.messaging.simp.annotation.SubscribeMapping",
            )

        private val SPRING_RUNNER_INTERFACES =
            setOf(
                "org.springframework.boot.CommandLineRunner",
                "org.springframework.boot.ApplicationRunner",
            )

        private val SPRING_APPLICATION_LISTENER_INTERFACES =
            setOf(
                "org.springframework.context.ApplicationListener",
            )

        private val JAXRS_PATH_ANNOTATIONS =
            setOf(
                "javax.ws.rs.Path",
                "jakarta.ws.rs.Path",
            )

        private val JAXRS_HTTP_METHOD_ANNOTATIONS =
            setOf(
                "javax.ws.rs.GET",
                "javax.ws.rs.POST",
                "javax.ws.rs.PUT",
                "javax.ws.rs.DELETE",
                "javax.ws.rs.PATCH",
                "javax.ws.rs.OPTIONS",
                "javax.ws.rs.HEAD",
                "jakarta.ws.rs.GET",
                "jakarta.ws.rs.POST",
                "jakarta.ws.rs.PUT",
                "jakarta.ws.rs.DELETE",
                "jakarta.ws.rs.PATCH",
                "jakarta.ws.rs.OPTIONS",
                "jakarta.ws.rs.HEAD",
            )

        private val JAXRS_HTTP_METHOD_META_ANNOTATIONS =
            setOf(
                "javax.ws.rs.HttpMethod",
                "jakarta.ws.rs.HttpMethod",
            )

        private val MICRONAUT_CONTROLLER_ANNOTATIONS =
            setOf(
                "io.micronaut.http.annotation.Controller",
            )

        private val MICRONAUT_MAPPING_ANNOTATIONS =
            setOf(
                "io.micronaut.http.annotation.Get",
                "io.micronaut.http.annotation.Post",
                "io.micronaut.http.annotation.Put",
                "io.micronaut.http.annotation.Delete",
                "io.micronaut.http.annotation.Patch",
                "io.micronaut.http.annotation.Options",
                "io.micronaut.http.annotation.Head",
                "io.micronaut.http.annotation.Trace",
            )

        private val QUARKUS_ROUTE_ANNOTATIONS =
            setOf(
                "io.quarkus.vertx.web.Route",
                "io.quarkus.vertx.web.RouteFilter",
            )

        private val HTTP_SERVLET_METHOD_NAMES =
            setOf(
                "service",
                "doGet",
                "doPost",
                "doPut",
                "doDelete",
                "doPatch",
                "doHead",
                "doOptions",
                "doTrace",
            )

        private val HTTP_SERVLET_CLASS_FQNS =
            setOf(
                "javax.servlet.http.HttpServlet",
                "jakarta.servlet.http.HttpServlet",
            )

        private val SERVLET_FILTER_CLASS_FQNS =
            setOf(
                "javax.servlet.Filter",
                "jakarta.servlet.Filter",
            )

        private val SERVLET_CLASS_FQNS =
            setOf(
                "javax.servlet.Servlet",
                "jakarta.servlet.Servlet",
            )

        private val WEBSOCKET_ENDPOINT_ANNOTATIONS =
            setOf(
                "javax.websocket.server.ServerEndpoint",
                "jakarta.websocket.server.ServerEndpoint",
            )

        private val WEBSOCKET_HANDLER_ANNOTATIONS =
            setOf(
                "javax.websocket.OnOpen",
                "javax.websocket.OnClose",
                "javax.websocket.OnMessage",
                "javax.websocket.OnError",
                "jakarta.websocket.OnOpen",
                "jakarta.websocket.OnClose",
                "jakarta.websocket.OnMessage",
                "jakarta.websocket.OnError",
            )

        private const val GRPC_STREAM_OBSERVER_FQN = "io.grpc.stub.StreamObserver"

        private val GRPC_SERVICE_CLASS_FQNS =
            setOf(
                "io.grpc.BindableService",
            )

        private val DUBBO_SERVICE_UNAMBIGUOUS_ANNOTATIONS =
            setOf(
                "org.apache.dubbo.config.annotation.DubboService",
            )

        private val DUBBO_SERVICE_EXACT_ONLY_ANNOTATIONS =
            setOf(
                "org.apache.dubbo.config.annotation.Service",
                "com.alibaba.dubbo.config.annotation.Service",
            )

        private const val MAX_DYNAMIC_DISPATCH_TARGETS = 40

        private const val THIS_RECEIVER_TOKEN = "__secrux_receiver_this__"
        private const val OTHER_RECEIVER_TOKEN = "__secrux_receiver_other__"
        private const val RETURN_VALUE_TOKEN = "__secrux_return__"

        fun getInstance(project: Project): CallGraphService = project.service()
    }
}
