import { apiClient } from "@/lib/api-client"

export type ReportFormat = "HTML" | "PDF" | "DOCX"
export type ReportDetailLevel = "SUMMARY" | "FULL"

export interface ReportExportRequest {
  findingIds: string[]
  format: ReportFormat
  detailLevel: ReportDetailLevel
  includeCode?: boolean
  includeDataFlow?: boolean
  title?: string
  locale?: string
}

export async function exportReport(payload: ReportExportRequest) {
  return apiClient.post<Blob>("/reports/export", payload, { responseType: "blob" })
}

export async function exportTaskReport(taskId: string, payload: ReportExportRequest) {
  return apiClient.post<Blob>(`/tasks/${taskId}/reports/export`, payload, { responseType: "blob" })
}

export async function exportProjectReport(projectId: string, payload: ReportExportRequest) {
  return apiClient.post<Blob>(`/projects/${projectId}/reports/export`, payload, { responseType: "blob" })
}

export async function exportRepoReport(projectId: string, repoId: string, payload: ReportExportRequest) {
  return apiClient.post<Blob>(`/projects/${projectId}/repos/${repoId}/reports/export`, payload, {
    responseType: "blob",
  })
}
