package com.secrux.service.enrichment

import com.sun.source.tree.ClassTree
import com.sun.source.tree.CompilationUnitTree
import com.sun.source.tree.ConditionalExpressionTree
import com.sun.source.tree.ExpressionTree
import com.sun.source.tree.IfTree
import com.sun.source.tree.IdentifierTree
import com.sun.source.tree.MemberSelectTree
import com.sun.source.tree.MethodInvocationTree
import com.sun.source.tree.MethodTree
import com.sun.source.tree.NewClassTree
import com.sun.source.tree.SwitchExpressionTree
import com.sun.source.tree.SwitchTree
import com.sun.source.tree.VariableTree
import com.sun.source.util.JavacTask
import com.sun.source.util.SourcePositions
import com.sun.source.util.TreePathScanner
import com.sun.source.util.TreeScanner
import com.sun.source.util.Trees
import java.io.StringWriter
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.LinkedHashMap
import javax.tools.DiagnosticListener
import javax.tools.JavaFileObject
import javax.tools.SimpleJavaFileObject
import javax.tools.ToolProvider

internal class JavaAstIndex private constructor(
    val path: Path,
    private val source: String,
    private val unit: CompilationUnitTree,
    private val positions: SourcePositions,
    private val methodInfos: List<MethodInfo>,
    private val classInfos: List<ClassInfo>,
) {
    fun findEnclosingMethod(targetLine: Int): MethodInfo? =
        methodInfos.firstOrNull { targetLine in it.startLine..it.endLine }

    fun collectInvocations(method: MethodTree): List<InvocationInfo> {
        val invocations = mutableListOf<InvocationInfo>()
        val lineMap = unit.lineMap
        object : TreeScanner<Unit, Unit>() {
            override fun visitMethodInvocation(node: MethodInvocationTree, p: Unit?) {
                val start = positions.getStartPosition(unit, node).takeIf { it >= 0 } ?: return
                val end = positions.getEndPosition(unit, node).takeIf { it >= 0 } ?: return
                val line = lineMap.getLineNumber(start).toInt()
                invocations.add(
                    InvocationInfo(
                        line = line,
                        text = source.substring(start.toInt(), minOf(end.toInt(), source.length)).trim().take(600),
                        argIdentifiers = extractArgIdentifiers(node.arguments),
                    )
                )
                super.visitMethodInvocation(node, p)
            }

            override fun visitNewClass(node: NewClassTree, p: Unit?) {
                val start = positions.getStartPosition(unit, node).takeIf { it >= 0 } ?: return
                val end = positions.getEndPosition(unit, node).takeIf { it >= 0 } ?: return
                val line = lineMap.getLineNumber(start).toInt()
                invocations.add(
                    InvocationInfo(
                        line = line,
                        text = source.substring(start.toInt(), minOf(end.toInt(), source.length)).trim().take(600),
                        argIdentifiers = extractArgIdentifiers(node.arguments),
                    )
                )
                super.visitNewClass(node, p)
            }
        }.scan(method, Unit)
        return invocations.sortedBy { it.line }
    }

    fun collectConditions(method: MethodTree): List<ConditionInfo> {
        val lineMap = unit.lineMap
        val conditions = mutableListOf<ConditionInfo>()
        object : TreeScanner<Unit, Unit>() {
            override fun visitIf(node: IfTree, p: Unit?) {
                conditions.add(extractCondition(node.condition))
                super.visitIf(node, p)
            }

            override fun visitConditionalExpression(node: ConditionalExpressionTree, p: Unit?) {
                conditions.add(extractCondition(node.condition))
                super.visitConditionalExpression(node, p)
            }

            override fun visitSwitch(node: SwitchTree, p: Unit?) {
                conditions.add(extractCondition(node.expression))
                super.visitSwitch(node, p)
            }

            override fun visitSwitchExpression(node: SwitchExpressionTree, p: Unit?) {
                conditions.add(extractCondition(node.expression))
                super.visitSwitchExpression(node, p)
            }

            private fun extractCondition(expr: ExpressionTree): ConditionInfo {
                val start = positions.getStartPosition(unit, expr).takeIf { it >= 0 } ?: 0L
                val end = positions.getEndPosition(unit, expr).takeIf { it >= 0 } ?: start
                val line = lineMap.getLineNumber(start).toInt()
                val text = source.substring(start.toInt(), minOf(end.toInt(), source.length)).trim().take(600)
                val external = collectExternalFromExpression(expr)
                return ConditionInfo(line = line, text = text, externalSymbols = external)
            }
        }.scan(method, Unit)
        return conditions.sortedBy { it.line }
    }

    data class Neighborhood(
        val conditions: List<Map<String, Any?>>,
        val invocations: List<Map<String, Any?>>,
        val externalSymbols: Set<String>
    )

    fun buildNeighborhood(method: MethodTree, focusLine: Int, window: Int): Neighborhood {
        val localsAndParams = collectLocalsAndParams(method)
        val conditions =
            collectConditions(method)
                .filter { kotlin.math.abs(it.line - focusLine) <= window }
                .map { cond ->
                    val external =
                        cond.externalSymbols
                            .map { normalizeJavaSymbol(it) }
                            .filter { it.isNotBlank() && it !in localsAndParams }
                            .distinct()
                            .take(20)
                    mapOf(
                        "line" to cond.line,
                        "text" to cond.text,
                        "externalSymbols" to external
                    )
                }
        val invocations =
            collectInvocations(method)
                .filter { kotlin.math.abs(it.line - focusLine) <= window }
                .map { inv ->
                    val args =
                        inv.argIdentifiers
                            .map { normalizeJavaSymbol(it) }
                            .filter { it.isNotBlank() && it !in localsAndParams }
                            .distinct()
                            .take(20)
                    mapOf(
                        "line" to inv.line,
                        "text" to inv.text,
                        "argIdentifiers" to args
                    )
                }
        val symbols =
            (conditions.flatMap { (it["externalSymbols"] as? List<*>)?.filterIsInstance<String>().orEmpty() } +
                invocations.flatMap { (it["argIdentifiers"] as? List<*>)?.filterIsInstance<String>().orEmpty() })
                .filter { it.isNotBlank() }
                .toSet()
        return Neighborhood(
            conditions = conditions,
            invocations = invocations,
            externalSymbols = symbols
        )
    }

    private fun collectLocalsAndParams(method: MethodTree): Set<String> {
        val locals = LinkedHashSet<String>()
        val params = method.parameters.mapNotNull { it.name?.toString() }.toSet()
        object : TreeScanner<Unit, Unit>() {
            override fun visitVariable(node: VariableTree, p: Unit?) {
                node.name?.toString()?.let { locals.add(it) }
                super.visitVariable(node, p)
            }
        }.scan(method, Unit)
        return (locals + params).toSet()
    }

    fun collectExternalSymbols(method: MethodTree): Set<String> {
        val locals = LinkedHashSet<String>()
        val params = method.parameters.mapNotNull { it.name?.toString() }.toSet()
        object : TreeScanner<Unit, Unit>() {
            override fun visitVariable(node: VariableTree, p: Unit?) {
                node.name?.toString()?.let { locals.add(it) }
                super.visitVariable(node, p)
            }
        }.scan(method, Unit)
        val external = LinkedHashSet<String>()
        object : TreeScanner<Unit, Unit>() {
            override fun visitMemberSelect(node: MemberSelectTree, p: Unit?) {
                external.add(node.toString())
                super.visitMemberSelect(node, p)
            }

            override fun visitIdentifier(node: IdentifierTree, p: Unit?) {
                val name = node.name.toString()
                if (name !in locals && name !in params && name !in setOf("true", "false", "null")) {
                    external.add(name)
                }
                super.visitIdentifier(node, p)
            }
        }.scan(method, Unit)
        return external.filter { it.isNotBlank() }.toCollection(LinkedHashSet())
    }

    fun findFieldDefinitionsAround(method: MethodTree, symbols: List<String>): List<Map<String, Any?>> {
        val classInfo = classInfos.firstOrNull { method in it.methods } ?: return emptyList()
        val symbolSet = symbols.toSet()
        val defs = mutableListOf<Map<String, Any?>>()
        classInfo.fields.forEach { field ->
            if (field.name !in symbolSet) return@forEach
            val text = field.text.take(8000)
            defs.add(
                mapOf(
                    "name" to field.name,
                    "line" to field.line,
                    "text" to text,
                )
            )
        }
        return defs
    }

    private fun collectExternalFromExpression(expr: ExpressionTree): List<String> {
        val external = LinkedHashSet<String>()
        object : TreeScanner<Unit, Unit>() {
            override fun visitMemberSelect(node: MemberSelectTree, p: Unit?) {
                external.add(node.toString())
                super.visitMemberSelect(node, p)
            }

            override fun visitIdentifier(node: IdentifierTree, p: Unit?) {
                val name = node.name.toString()
                if (name !in setOf("true", "false", "null")) {
                    external.add(name)
                }
                super.visitIdentifier(node, p)
            }
        }.scan(expr, Unit)
        return external.take(20)
    }

    private fun extractArgIdentifiers(args: List<ExpressionTree>?): List<String> {
        if (args == null) return emptyList()
        val ids = LinkedHashSet<String>()
        args.forEach { arg ->
            object : TreeScanner<Unit, Unit>() {
                override fun visitMemberSelect(node: MemberSelectTree, p: Unit?) {
                    ids.add(node.toString())
                    super.visitMemberSelect(node, p)
                }
            }.scan(arg, Unit)
            val s = arg.toString()
            if (s.matches(Regex("[A-Za-z_$][A-Za-z0-9_$]*"))) {
                ids.add(s)
            }
        }
        return ids.take(10)
    }

    data class MethodInfo(
        val tree: MethodTree,
        val name: String,
        val signature: String,
        val startLine: Int,
        val endLine: Int,
        val text: String,
    ) {
        fun toSummary(maxChars: Int): Map<String, Any?> =
            mapOf(
                "name" to name,
                "signature" to signature,
                "startLine" to startLine,
                "endLine" to endLine,
                "text" to text.take(maxChars),
                "truncated" to (text.length > maxChars),
            )
    }

    data class InvocationInfo(
        val line: Int,
        val text: String,
        val argIdentifiers: List<String>,
    )

    data class ConditionInfo(
        val line: Int,
        val text: String,
        val externalSymbols: List<String>,
    )

    data class FieldInfo(
        val name: String,
        val line: Int,
        val text: String,
    )

    data class ClassInfo(
        val tree: ClassTree,
        val methods: List<MethodTree>,
        val fields: List<FieldInfo>,
    )

    companion object {
        private const val MAX_CACHE = 40
        private val cache = object : LinkedHashMap<String, JavaAstIndex>(MAX_CACHE, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, JavaAstIndex>?): Boolean = size > MAX_CACHE
        }

        fun get(path: Path): JavaAstIndex {
            val key = path.toAbsolutePath().normalize().toString()
            synchronized(cache) {
                cache[key]?.let { return it }
            }
            val index = build(path)
            synchronized(cache) {
                cache[key] = index
            }
            return index
        }

        private fun build(path: Path): JavaAstIndex {
            val source = Files.readString(path, StandardCharsets.UTF_8)
            val compiler = ToolProvider.getSystemJavaCompiler() ?: throw IllegalStateException("Java compiler not available")
            val fileObject = InMemoryJavaFileObject(path.fileName.toString(), source)
            val unitsAndContext =
                compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8).use { fileManager ->
                    val out = StringWriter()
                    val diagnosticListener = DiagnosticListener<JavaFileObject> { _ -> }
                    val task =
                        compiler.getTask(
                            out,
                            fileManager,
                            diagnosticListener,
                            listOf("-proc:none", "-Xlint:none"),
                            null,
                            listOf(fileObject)
                        ) as JavacTask
                    val units = task.parse().toList()
                    val unit = units.firstOrNull() ?: throw IllegalStateException("No compilation unit parsed")
                    val trees = Trees.instance(task)
                    val positions = trees.sourcePositions
                    Pair(unit, positions)
                }
            val (unit, positions) = unitsAndContext
            val lineMap = unit.lineMap

            val methodInfos = mutableListOf<MethodInfo>()
            val classInfos = mutableListOf<ClassInfo>()

            object : TreePathScanner<Unit, Unit>() {
                override fun visitClass(node: ClassTree, p: Unit?) {
                    val fields = mutableListOf<FieldInfo>()
                    val methods = mutableListOf<MethodTree>()
                    node.members.forEach { member ->
                        when (member) {
                            is VariableTree -> {
                                val start = positions.getStartPosition(unit, member).takeIf { it >= 0 } ?: return@forEach
                                val end = positions.getEndPosition(unit, member).takeIf { it >= 0 } ?: start
                                val line = lineMap.getLineNumber(start).toInt()
                                val text = source.substring(start.toInt(), minOf(end.toInt(), source.length)).trim()
                                fields.add(FieldInfo(name = member.name.toString(), line = line, text = text))
                            }

                            is MethodTree -> {
                                methods.add(member)
                            }
                        }
                    }
                    classInfos.add(ClassInfo(tree = node, methods = methods.toList(), fields = fields.toList()))
                    return super.visitClass(node, p)
                }

                override fun visitMethod(node: MethodTree, p: Unit?) {
                    val start = positions.getStartPosition(unit, node).takeIf { it >= 0 }
                    if (start == null) {
                        super.visitMethod(node, p)
                        return Unit
                    }
                    val end = positions.getEndPosition(unit, node).takeIf { it >= 0 } ?: start
                    val startLine = lineMap.getLineNumber(start).toInt()
                    val endLine = lineMap.getLineNumber(end).toInt()
                    val text = source.substring(start.toInt(), minOf(end.toInt(), source.length)).trim()
                    val signature = node.toString().lineSequence().firstOrNull()?.trim()?.take(200) ?: node.name.toString()
                    methodInfos.add(
                        MethodInfo(
                            tree = node,
                            name = node.name.toString(),
                            signature = signature,
                            startLine = startLine,
                            endLine = maxOf(endLine, startLine),
                            text = text,
                        )
                    )
                    super.visitMethod(node, p)
                    return Unit
                }
            }.scan(unit, Unit)

            return JavaAstIndex(
                path = path,
                source = source,
                unit = unit,
                positions = positions,
                methodInfos = methodInfos.sortedBy { it.startLine },
                classInfos = classInfos,
            )
        }
    }
}

private class InMemoryJavaFileObject(
    name: String,
    private val source: String
) : SimpleJavaFileObject(URI.create("string:///$name"), JavaFileObject.Kind.SOURCE) {
    override fun getCharContent(ignoreEncodingErrors: Boolean): CharSequence = source
}

