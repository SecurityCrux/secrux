package com.secrux.service

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder
import com.secrux.dto.FindingDetailResponse
import com.secrux.dto.ReportDetailLevel
import com.secrux.dto.ReportExportRequest
import com.secrux.dto.ReportFormat
import com.secrux.security.SecruxPrincipal
import org.apache.poi.xwpf.usermodel.ParagraphAlignment
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Service
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

@Service
class ReportService(
    private val findingService: FindingService,
    private val clock: Clock,
) {
    fun export(
        principal: SecruxPrincipal,
        request: ReportExportRequest,
    ): ResponseEntity<ByteArrayResource> {
        val safeIds = request.findingIds.take(200) // hard guard
        val details = safeIds.map { findingService.getFindingDetail(principal.tenantId, it) }
        val now = OffsetDateTime.now(clock)
        val html =
            ReportTemplateRenderer.renderHtml(
                title = request.title ?: "Secrux Scan Report",
                generatedAt = now,
                requestedBy = principal.username ?: principal.email ?: principal.userId.toString(),
                detailLevel = request.detailLevel,
                includeCode = request.includeCode,
                includeDataFlow = request.includeDataFlow,
                findings = details,
                locale = request.locale,
            )
        val payload =
            when (request.format) {
                ReportFormat.HTML -> {
                    ExportPayload(
                        bytes = html.toByteArray(StandardCharsets.UTF_8),
                        contentType = MediaType.TEXT_HTML_VALUE,
                        extension = "html",
                    )
                }

                ReportFormat.PDF -> {
                    ExportPayload(
                        bytes = renderPdf(html),
                        contentType = "application/pdf",
                        extension = "pdf",
                    )
                }

                ReportFormat.DOCX -> {
                    ExportPayload(
                        bytes = renderDocx(details, request),
                        contentType = "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                        extension = "docx",
                    )
                }
            }
        val filename =
            buildString {
                append("secrux-report-")
                append(now.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")))
                append(".")
                append(payload.extension)
            }
        val body = ByteArrayResource(payload.bytes)
        return ResponseEntity
            .ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"$filename\"")
            .contentLength(payload.bytes.size.toLong())
            .contentType(MediaType.parseMediaType(payload.contentType))
            .body(body)
    }

    private fun renderPdf(html: String): ByteArray {
        val out = ByteArrayOutputStream()
        val builder =
            PdfRendererBuilder()
                .useFastMode()
                .withHtmlContent(html, null)
                .toStream(out)
        registerFonts(builder)
        builder.run()
        return out.toByteArray()
    }

    private fun renderDocx(
        findings: List<FindingDetailResponse>,
        request: ReportExportRequest,
    ): ByteArray {
        XWPFDocument().use { doc ->
            doc.createParagraph().apply {
                alignment = ParagraphAlignment.CENTER
                createRun().apply {
                    isBold = true
                    fontSize = 16
                    setText(request.title ?: "Secrux Scan Report")
                }
            }
            findings.forEach { finding ->
                doc.createParagraph().apply {
                    spacingBefore = 200
                    createRun().apply {
                        isBold = true
                        fontSize = 12
                        setText("${finding.ruleId ?: "Finding"} (${finding.severity})")
                    }
                }
                doc.createParagraph().createRun().setText("Finding ID: ${finding.findingId}")
                doc.createParagraph().createRun().setText(
                    "Location: ${finding.location.path ?: "unknown"}:${finding.location.line ?: finding.location.startLine ?: ""}",
                )
                if (request.includeCode) {
                    finding.codeSnippet?.let { snippet ->
                        doc.createParagraph().createRun().setText("Code snippet (${snippet.startLine}-${snippet.endLine}):")
                        snippet.lines.forEach { line ->
                            doc.createParagraph().createRun().setText("${line.lineNumber}: ${line.content}")
                        }
                    }
                }
                if (request.includeDataFlow && request.detailLevel == ReportDetailLevel.FULL) {
                    if (finding.dataFlowNodes.isNotEmpty()) {
                        doc.createParagraph().createRun().setText("Data flow:")
                        finding.dataFlowNodes.forEach { node ->
                            doc.createParagraph().createRun().setText("- ${node.label} (${node.file}:${node.line})")
                        }
                    }
                }
            }
            val baos = ByteArrayOutputStream()
            doc.write(baos)
            return baos.toByteArray()
        }
    }

    private data class ExportPayload(
        val bytes: ByteArray,
        val contentType: String,
        val extension: String,
    )

    private fun registerFonts(builder: PdfRendererBuilder) {
        val classpathFonts =
            listOf(
                "fonts/NotoSansSC-Regular.otf",
                "fonts/NotoSansSC-Regular.ttf",
                "fonts/NotoSans-Regular.ttf",
            )
        var registered = false
        classpathFonts.forEach { path ->
            runCatching {
                javaClass.classLoader.getResourceAsStream(path)?.use { stream ->
                    val bytes = stream.readAllBytes()
                    builder.useFont(
                        { ByteArrayInputStream(bytes) },
                        "NotoSansSC",
                    )
                    registered = true
                }
            }
        }

        val candidates =
            listOf(
                "/System/Library/Fonts/PingFang.ttc",
                "/System/Library/Fonts/STHeiti Light.ttc",
                "/usr/share/fonts/truetype/wqy/wqy-microhei.ttc",
                "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
            )
        candidates.forEach { path ->
            val file = File(path)
            if (file.exists()) {
                builder.useFont(file, "NotoSansSC")
                registered = true
            }
        }

        if (!registered) {
            // fallback: renderer default fonts; non-ASCII may still break without a valid font
        }
    }

    fun exportForTask(
        principal: SecruxPrincipal,
        taskId: UUID,
        request: ReportExportRequest,
    ): ResponseEntity<ByteArrayResource> {
        val ids = pickIds(request.findingIds) { findingService.listFindingIdsByTask(principal.tenantId, taskId) }
        return export(principal, request.copy(findingIds = ids))
    }

    fun exportForProject(
        principal: SecruxPrincipal,
        projectId: UUID,
        request: ReportExportRequest,
    ): ResponseEntity<ByteArrayResource> {
        val ids = pickIds(request.findingIds) { findingService.listFindingIdsByProject(principal.tenantId, projectId) }
        return export(principal, request.copy(findingIds = ids))
    }

    fun exportForRepo(
        principal: SecruxPrincipal,
        repoId: UUID,
        request: ReportExportRequest,
    ): ResponseEntity<ByteArrayResource> {
        val ids = pickIds(request.findingIds) { findingService.listFindingIdsByRepo(principal.tenantId, repoId) }
        return export(principal, request.copy(findingIds = ids))
    }

    private fun pickIds(
        given: List<UUID>,
        resolver: () -> List<UUID>,
    ): List<UUID> = if (given.isNotEmpty()) given else resolver()
}
