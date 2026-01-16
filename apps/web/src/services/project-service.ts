import type {
  ApiResponse,
  Project,
  ProjectOverviewResponse,
  ProjectUpsertPayload,
  RepositoryGitMetadata,
  RepositoryResponse,
  RepositoryUpdatePayload,
  RepositoryUpsertPayload,
} from "@/types/api"
import { apiClient } from "@/lib/api-client"

export async function listProjects() {
  const { data } = await apiClient.get<ApiResponse<Project[]>>("/projects")
  return data.data ?? []
}

export async function getProject(projectId: string) {
  const { data } = await apiClient.get<ApiResponse<Project>>(`/projects/${projectId}`)
  return data.data
}

export async function createProject(payload: ProjectUpsertPayload) {
  const { data } = await apiClient.post<ApiResponse<Project>>("/projects", payload)
  return data.data
}

export async function updateProject(projectId: string, payload: ProjectUpsertPayload) {
  const { data } = await apiClient.put<ApiResponse<Project>>(`/projects/${projectId}`, payload)
  return data.data
}

export async function deleteProject(projectId: string) {
  await apiClient.delete<ApiResponse<unknown>>(`/projects/${projectId}`)
}

export async function listRepositories(projectId: string) {
  const { data } = await apiClient.get<ApiResponse<RepositoryResponse[]>>(`/projects/${projectId}/repositories`)
  return data.data ?? []
}

export async function getProjectOverview(projectId: string) {
  const { data } = await apiClient.get<ApiResponse<ProjectOverviewResponse>>(`/projects/${projectId}/overview`)
  return data.data
}

export async function createRepository(projectId: string, payload: RepositoryUpsertPayload) {
  const { data } = await apiClient.post<ApiResponse<RepositoryResponse>>(
    `/projects/${projectId}/repositories`,
    payload
  )
  return data.data
}

export async function updateRepository(
  projectId: string,
  repoId: string,
  payload: RepositoryUpdatePayload
) {
  const { data } = await apiClient.put<ApiResponse<RepositoryResponse>>(
    `/projects/${projectId}/repositories/${repoId}`,
    payload
  )
  return data.data
}

export async function deleteRepository(projectId: string, repoId: string) {
  await apiClient.delete<ApiResponse<unknown>>(`/projects/${projectId}/repositories/${repoId}`)
}

export async function getRepositoryGitMetadata(projectId: string, repoId: string, refresh?: boolean) {
  const { data } = await apiClient.get<ApiResponse<RepositoryGitMetadata>>(
    `/projects/${projectId}/repositories/${repoId}/git`,
    { params: { refresh } }
  )
  return data.data
}
