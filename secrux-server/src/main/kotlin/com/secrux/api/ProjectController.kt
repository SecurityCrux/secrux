package com.secrux.api

import com.github.xiaoymin.knife4j.annotations.ApiOperationSupport
import com.secrux.common.ApiResponse
import com.secrux.dto.ProjectUpsertRequest
import com.secrux.dto.RepositoryUpdateRequest
import com.secrux.dto.RepositoryUpsertRequest
import com.secrux.service.ProjectService
import com.secrux.service.RepositoryGitService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID
import com.secrux.security.AuthorizationAction
import com.secrux.security.AuthorizationService
import com.secrux.security.SecruxPrincipal
import com.secrux.security.projectResource
import com.secrux.security.repositoryResource

@RestController
@RequestMapping("/projects")
@Tag(name = "Project APIs", description = "Project and repository management")
@Validated
class ProjectController(
    private val projectService: ProjectService,
    private val authorizationService: AuthorizationService,
    private val repositoryGitService: RepositoryGitService
) {

    @PostMapping
    @Operation(summary = "Create project", description = "Create a project under tenant")
    @ApiOperationSupport(order = 1)
    fun createProject(
        @AuthenticationPrincipal principal: SecruxPrincipal,
        @Valid @RequestBody request: ProjectUpsertRequest
    ): ApiResponse<*> {
        authorizationService.require(
            principal = principal,
            action = AuthorizationAction.PROJECT_CREATE,
            resource = principal.projectResource(),
            context = mapOf("name" to request.name)
        )
        return ApiResponse(data = projectService.createProject(principal.tenantId, request))
    }

    @PutMapping("/{projectId}")
    @Operation(summary = "Update project", description = "Update project metadata")
    @ApiOperationSupport(order = 2)
    fun updateProject(
        @AuthenticationPrincipal principal: SecruxPrincipal,
        @PathVariable projectId: UUID,
        @Valid @RequestBody request: ProjectUpsertRequest
    ): ApiResponse<*> {
        authorizationService.require(
            principal = principal,
            action = AuthorizationAction.PROJECT_UPDATE,
            resource = principal.projectResource(projectId)
        )
        return ApiResponse(data = projectService.updateProject(principal.tenantId, projectId, request))
    }

    @GetMapping
    @Operation(summary = "List projects", description = "List tenant projects")
    @ApiOperationSupport(order = 3)
    fun listProjects(
        @AuthenticationPrincipal principal: SecruxPrincipal
    ): ApiResponse<*> {
        authorizationService.require(
            principal = principal,
            action = AuthorizationAction.PROJECT_READ,
            resource = principal.projectResource()
        )
        return ApiResponse(data = projectService.listProjects(principal.tenantId))
    }

    @GetMapping("/{projectId}")
    @Operation(summary = "Get project", description = "Get project by id")
    @ApiOperationSupport(order = 4)
    fun getProject(
        @AuthenticationPrincipal principal: SecruxPrincipal,
        @PathVariable projectId: UUID
    ): ApiResponse<*> {
        authorizationService.require(
            principal = principal,
            action = AuthorizationAction.PROJECT_READ,
            resource = principal.projectResource(projectId)
        )
        return ApiResponse(data = projectService.getProject(principal.tenantId, projectId))
    }

    @PostMapping("/{projectId}/repositories")
    @Operation(summary = "Create repository", description = "Create repository under project")
    @ApiOperationSupport(order = 5)
    fun createRepository(
        @AuthenticationPrincipal principal: SecruxPrincipal,
        @PathVariable projectId: UUID,
        @Valid @RequestBody request: RepositoryUpsertRequest
    ): ApiResponse<*> {
        authorizationService.require(
            principal = principal,
            action = AuthorizationAction.REPOSITORY_MANAGE,
            resource = principal.repositoryResource(projectId)
        )
        return ApiResponse(
            data = projectService.createRepository(
                principal.tenantId,
                request.copy(projectId = projectId)
            )
        )
    }

    @DeleteMapping("/{projectId}")
    @Operation(summary = "Delete project", description = "Soft delete project and its repositories")
    @ApiOperationSupport(order = 8)
    fun deleteProject(
        @AuthenticationPrincipal principal: SecruxPrincipal,
        @PathVariable projectId: UUID
    ): ApiResponse<*> {
        authorizationService.require(
            principal = principal,
            action = AuthorizationAction.PROJECT_UPDATE,
            resource = principal.projectResource(projectId)
        )
        projectService.deleteProject(principal.tenantId, projectId)
        return ApiResponse<Unit>()
    }

    @GetMapping("/{projectId}/repositories")
    @Operation(summary = "List repositories", description = "List repositories for project")
    @ApiOperationSupport(order = 6)
    fun listRepositories(
        @AuthenticationPrincipal principal: SecruxPrincipal,
        @PathVariable projectId: UUID
    ): ApiResponse<*> {
        authorizationService.require(
            principal = principal,
            action = AuthorizationAction.REPOSITORY_READ,
            resource = principal.repositoryResource(projectId)
        )
        return ApiResponse(data = projectService.listRepositories(principal.tenantId, projectId))
    }

    @PutMapping("/{projectId}/repositories/{repoId}")
    @Operation(summary = "Update repository", description = "Update repository metadata")
    @ApiOperationSupport(order = 7)
    fun updateRepository(
        @AuthenticationPrincipal principal: SecruxPrincipal,
        @PathVariable projectId: UUID,
        @PathVariable repoId: UUID,
        @Valid @RequestBody request: RepositoryUpdateRequest
    ): ApiResponse<*> {
        authorizationService.require(
            principal = principal,
            action = AuthorizationAction.REPOSITORY_MANAGE,
            resource = principal.repositoryResource(projectId)
        )
        return ApiResponse(
            data = projectService.updateRepository(principal.tenantId, projectId, repoId, request)
        )
    }

    @DeleteMapping("/{projectId}/repositories/{repoId}")
    @Operation(summary = "Delete repository", description = "Soft delete repository under project")
    @ApiOperationSupport(order = 9)
    fun deleteRepository(
        @AuthenticationPrincipal principal: SecruxPrincipal,
        @PathVariable projectId: UUID,
        @PathVariable repoId: UUID
    ): ApiResponse<*> {
        authorizationService.require(
            principal = principal,
            action = AuthorizationAction.REPOSITORY_MANAGE,
            resource = principal.repositoryResource(projectId)
        )
        projectService.deleteRepository(principal.tenantId, projectId, repoId)
        return ApiResponse<Unit>()
    }

    @GetMapping("/{projectId}/repositories/{repoId}/git")
    @Operation(summary = "Get repository git metadata", description = "Fetch git branches, tags, commits via JGit")
    @ApiOperationSupport(order = 10)
    fun getRepositoryGitMetadata(
        @AuthenticationPrincipal principal: SecruxPrincipal,
        @PathVariable projectId: UUID,
        @PathVariable repoId: UUID,
        @RequestParam(defaultValue = "false") refresh: Boolean
    ): ApiResponse<*> {
        authorizationService.require(
            principal = principal,
            action = AuthorizationAction.REPOSITORY_READ,
            resource = principal.repositoryResource(projectId)
        )
        return ApiResponse(
            data = repositoryGitService.getMetadata(principal.tenantId, projectId, repoId, refresh)
        )
    }
}

