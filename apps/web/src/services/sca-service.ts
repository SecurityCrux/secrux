import { apiClient } from "@/lib/api-client"
import type {
  ApiResponse,
  AiJobTicket,
  DependencyGraph,
  CodeSnippet,
  FindingListFilters,
  PageResponse,
  ScaIssueDetail,
  ScaIssueStatusUpdatePayload,
  ScaIssueSummary,
  ScaUsageEntry,
  UploadResponse,
} from "@/types/api"

export async function uploadScaArchive(projectId: string, file: File) {
  const form = new FormData()
  form.append("file", file)
  const { data } = await apiClient.post<ApiResponse<UploadResponse>>(`/projects/${projectId}/sca/uploads/archive`, form, {
    headers: { "Content-Type": "multipart/form-data" },
  })
  return data.data
}

export async function uploadScaSbom(projectId: string, file: File) {
  const form = new FormData()
  form.append("file", file)
  const { data } = await apiClient.post<ApiResponse<UploadResponse>>(`/projects/${projectId}/sca/uploads/sbom`, form, {
    headers: { "Content-Type": "multipart/form-data" },
  })
  return data.data
}

export async function getScaDependencyGraph(taskId: string) {
  const { data } = await apiClient.get<ApiResponse<DependencyGraph>>(`/sca/tasks/${taskId}/dependency-graph`)
  return data.data
}

export async function getScaTaskIssues(taskId: string, filters: FindingListFilters = {}) {
  const { data } = await apiClient.get<ApiResponse<PageResponse<ScaIssueSummary>>>(`/sca/tasks/${taskId}/issues`, {
    params: filters,
  })
  return data.data
}

export async function getScaTaskIssueDetail(taskId: string, issueId: string) {
  const { data } = await apiClient.get<ApiResponse<ScaIssueDetail>>(`/sca/tasks/${taskId}/issues/${issueId}`)
  return data.data
}

export async function updateScaTaskIssueStatus(taskId: string, issueId: string, payload: ScaIssueStatusUpdatePayload) {
  const { data } = await apiClient.patch<ApiResponse<ScaIssueSummary>>(`/sca/tasks/${taskId}/issues/${issueId}`, payload)
  return data.data
}

export async function getScaIssueDetail(issueId: string) {
  const { data } = await apiClient.get<ApiResponse<ScaIssueDetail>>(`/sca/issues/${issueId}`)
  return data.data
}

export async function updateScaIssueStatus(issueId: string, payload: ScaIssueStatusUpdatePayload) {
  const { data } = await apiClient.patch<ApiResponse<ScaIssueSummary>>(`/sca/issues/${issueId}`, payload)
  return data.data
}

export async function triggerScaIssueAiReview(issueId: string, payload?: { context?: string; mode?: string; agent?: string }) {
  const { data } = await apiClient.post<ApiResponse<AiJobTicket>>(`/sca/issues/${issueId}/ai-review`, payload)
  return data.data
}

export async function getScaIssueUsage(issueId: string, params: { limit?: number; offset?: number } = {}) {
  const { data } = await apiClient.get<ApiResponse<PageResponse<ScaUsageEntry>>>(`/sca/issues/${issueId}/usage`, { params })
  return data.data
}

export async function getScaIssueSnippet(
  issueId: string,
  params: {
    path: string
    line: number
    context?: number
    receiver?: string
    symbol?: string
    kind?: string
    language?: string
  }
) {
  const { data } = await apiClient.get<ApiResponse<CodeSnippet | null>>(`/sca/issues/${issueId}/snippet`, { params })
  return data.data
}

function parseContentDispositionFilename(raw: unknown): string | null {
  if (typeof raw !== "string") return null
  const parts = raw.split(";").map((part) => part.trim()).filter(Boolean)

  const filenameStar = parts.find((part) => part.toLowerCase().startsWith("filename*="))
  if (filenameStar) {
    let value = filenameStar.slice("filename*=".length).trim()
    value = value.replace(/^\"|\"$/g, "")
    const first = value.indexOf("'")
    const second = first >= 0 ? value.indexOf("'", first + 1) : -1
    const encoded = second >= 0 ? value.slice(second + 1) : value
    if (encoded) {
      try {
        return decodeURIComponent(encoded)
      } catch {
        return encoded
      }
    }
  }

  const filename = parts.find((part) => part.toLowerCase().startsWith("filename="))
  if (filename) {
    let value = filename.slice("filename=".length).trim()
    value = value.replace(/^\"|\"$/g, "")
    return value || null
  }

  return null
}

export async function downloadScaSbom(taskId: string) {
  const response = await apiClient.get(`/sca/tasks/${taskId}/sbom/download`, { responseType: "blob" })
  const fileName = parseContentDispositionFilename(response.headers["content-disposition"]) ?? `sbom-${taskId}.cdx.json`
  return { blob: response.data as Blob, fileName }
}
