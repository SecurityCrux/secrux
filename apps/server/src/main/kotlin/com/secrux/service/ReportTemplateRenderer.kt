package com.secrux.service

import com.secrux.domain.Severity
import com.secrux.dto.FindingDetailResponse
import com.secrux.dto.ReportDetailLevel
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

object ReportTemplateRenderer {

    fun renderHtml(
        title: String,
        generatedAt: OffsetDateTime,
        requestedBy: String,
        detailLevel: ReportDetailLevel,
        includeCode: Boolean,
        includeDataFlow: Boolean,
        findings: List<FindingDetailResponse>,
        locale: String?
    ): String {
        val lang = (locale ?: "en").lowercase()
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss XXX")
        val labels = labels(lang)
        val severityStats = findings.groupingBy { it.severity }.eachCount()
        val total = findings.size
        val rows =
            findings.mapIndexed { idx, finding ->
                buildFindingSection(idx + 1, finding, detailLevel, includeCode, includeDataFlow, labels)
            }.joinToString("\n")
        return """
            <!DOCTYPE html>
            <html lang="${escape(lang)}">
            <head>
              <meta charset="UTF-8" />
              <style>
                body { font-family: "NotoSansSC","Noto Sans SC","PingFang SC","Microsoft YaHei","Arial Unicode MS", Arial, sans-serif; color: #0f172a; margin: 32px; background: #f8fafc; }
                h1 { font-size: 26px; margin-bottom: 4px; }
                h2 { font-size: 18px; margin: 24px 0 8px; }
                h3 { font-size: 16px; margin: 16px 0 8px; }
                .muted { color: #6b7280; font-size: 12px; }
                .chip { display: inline-block; padding: 2px 8px; border-radius: 12px; font-size: 12px; background: #e2e8f0; color: #0f172a; }
                .severity-CRITICAL { background: #b91c1c; color: #fff; }
                .severity-HIGH { background: #dc2626; color: #fff; }
                .severity-MEDIUM { background: #d97706; color: #fff; }
                .severity-LOW { background: #0891b2; color: #fff; }
                .card { border: 1px solid #e5e7eb; border-radius: 12px; padding: 16px 18px; margin: 12px 0; background: #fff; box-shadow: 0 6px 18px rgba(15,23,42,0.04); }
                pre { background: #0b1021; color: #e5e7eb; padding: 12px; border-radius: 8px; overflow-x: auto; font-size: 12px; }
                code { font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, "Liberation Mono", "Courier New", monospace; }
                table { border-collapse: collapse; width: 100%; margin-top: 12px; }
                td, th { border: 1px solid #e5e7eb; padding: 8px; text-align: left; font-size: 12px; }
                th { background: #f1f5f9; }
                .section-title { margin-top: 24px; margin-bottom: 4px; font-size: 18px; }
                .toc { margin: 16px 0; padding: 12px; background: #fff; border: 1px solid #e2e8f0; border-radius: 10px; }
                .toc li { margin: 4px 0; }
                .meta-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 8px; margin-top: 8px; }
                .meta-item { font-size: 12px; color: #475569; }
                .cover { padding: 32px; background: linear-gradient(135deg, #0ea5e9, #1d4ed8); color: #f8fafc; border-radius: 14px; margin-bottom: 16px; }
                .cover h1 { color: #fff; }
                .cover .muted { color: #e2e8f0; }
              </style>
              <title>${escape(title)}</title>
            </head>
            <body>
              <section class="cover">
                <h1>${escape(title)}</h1>
                <div class="muted">${labels.generatedAt} ${escape(formatter.format(generatedAt))}</div>
                <div class="muted">${labels.requestedBy} ${escape(requestedBy)} · ${labels.detail}: ${labels.detailLevel(detailLevel)}</div>
              </section>
              <div class="card">
                <h2>${labels.findings} $total</h2>
                <div class="meta-grid">
                  <div class="meta-item">${labels.severityCritical}: ${severityStats[Severity.CRITICAL] ?: 0}</div>
                  <div class="meta-item">${labels.severityHigh}: ${severityStats[Severity.HIGH] ?: 0}</div>
                  <div class="meta-item">${labels.severityMedium}: ${severityStats[Severity.MEDIUM] ?: 0}</div>
                  <div class="meta-item">${labels.severityLow}: ${severityStats[Severity.LOW] ?: 0}</div>
                </div>
                <h3 class="section-title">${labels.toc}</h3>
                <ol class="toc">
                  ${findings.mapIndexed { idx, f -> "<li>${idx + 1}. ${escape(f.ruleId ?: labels.finding)} (${f.severity})</li>" }.joinToString("")}
                </ol>
              </div>
              <main>
                $rows
              </main>
            </body>
            </html>
        """.trimIndent()
    }

    private fun buildFindingSection(
        index: Int,
        finding: FindingDetailResponse,
        detailLevel: ReportDetailLevel,
        includeCode: Boolean,
        includeDataFlow: Boolean,
        labels: LabelSet
    ): String {
        val code = if (includeCode) buildCodeSnippet(finding, labels) else ""
        val dataFlow = if (includeDataFlow && detailLevel == ReportDetailLevel.FULL) buildDataFlow(finding, labels) else ""
        return """
            <section class="card">
              <h2>${index}. ${escape(finding.ruleId ?: labels.finding)} <span class="chip severity-${finding.severity}">${finding.severity}</span></h2>
              <div class="muted">${labels.findingId}: ${finding.findingId} · ${labels.source}: ${escape(finding.sourceEngine)} · ${labels.status}: ${finding.status}</div>
              <p>${labels.location}: ${escape(finding.location.path ?: labels.unknown)}:${escape((finding.location.line ?: finding.location.startLine ?: "").toString())}</p>
              $code
              $dataFlow
            </section>
        """.trimIndent()
    }

    private fun buildCodeSnippet(finding: FindingDetailResponse, labels: LabelSet): String {
        val snippet = finding.codeSnippet ?: return ""
        val lines = snippet.lines.joinToString("\n") { line ->
            val marker = if (line.highlight) ">>" else "  "
            "$marker ${line.lineNumber.toString().padStart(5, ' ')} ${line.content}"
        }
        return """
            <h3>${labels.codeSnippet} (${snippet.startLine}-${snippet.endLine})</h3>
            <pre><code>${escape(lines)}</code></pre>
        """.trimIndent()
    }

    private fun buildDataFlow(finding: FindingDetailResponse, labels: LabelSet): String {
        if (finding.dataFlowNodes.isEmpty()) return ""
        val nodes = finding.dataFlowNodes.joinToString("") { node ->
            "<li>${escape(node.label)} ${escape(node.file ?: "")}:${escape(node.line?.toString() ?: "")}</li>"
        }
        return """
            <h3>${labels.dataFlow}</h3>
            <ul>
              $nodes
            </ul>
        """.trimIndent()
    }

    private fun labels(locale: String): LabelSet =
        if (locale.startsWith("zh")) {
            LabelSet(
                generatedAt = "生成时间",
                requestedBy = "导出人",
                detail = "详情",
                detailSummary = "简略",
                detailFull = "详细",
                findings = "漏洞总数",
                severityCritical = "致命",
                severityHigh = "高",
                severityMedium = "中",
                severityLow = "低",
                evidence = "证据",
                finding = "漏洞",
                findingId = "漏洞 ID",
                source = "来源",
                status = "状态",
                location = "位置",
                unknown = "未知",
                codeSnippet = "代码片段",
                dataFlow = "数据流",
                toc = "目录"
            )
        } else {
            LabelSet(
                generatedAt = "Generated at",
                requestedBy = "Requested by",
                detail = "Detail",
                detailSummary = "Summary",
                detailFull = "Full",
                findings = "Findings",
                severityCritical = "Critical",
                severityHigh = "High",
                severityMedium = "Medium",
                severityLow = "Low",
                evidence = "Evidence",
                finding = "Finding",
                findingId = "Finding ID",
                source = "Source",
                status = "Status",
                location = "Location",
                unknown = "unknown",
                codeSnippet = "Code snippet",
                dataFlow = "Data flow",
                toc = "Contents"
            )
        }

    private data class LabelSet(
        val generatedAt: String,
        val requestedBy: String,
        val detail: String,
        val detailSummary: String,
        val detailFull: String,
        val findings: String,
        val severityCritical: String,
        val severityHigh: String,
        val severityMedium: String,
        val severityLow: String,
        val evidence: String,
        val finding: String,
        val findingId: String,
        val source: String,
        val status: String,
        val location: String,
        val unknown: String,
        val codeSnippet: String,
        val dataFlow: String,
        val toc: String
    ) {
        fun detailLevel(level: ReportDetailLevel): String =
            if (level == ReportDetailLevel.SUMMARY) detailSummary else detailFull
    }

    private fun escape(input: String): String =
        input
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
}
