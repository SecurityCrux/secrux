package com.secrux.security

import java.util.UUID

private fun mergeAttributes(
    attributes: Map<String, Any?>,
    vararg extras: Pair<String, Any?>
): Map<String, Any?> {
    val extraMap = extras.filter { it.second != null }.associate { it.first to it.second }
    return if (extraMap.isEmpty()) attributes else attributes + extraMap
}

fun SecruxPrincipal.authResource(
    type: String,
    resourceId: UUID? = null,
    projectId: UUID? = null,
    taskId: UUID? = null,
    attributes: Map<String, Any?> = emptyMap()
): AuthorizationResource =
    AuthorizationResource(
        tenantId = tenantId,
        type = type,
        resourceId = resourceId,
        projectId = projectId,
        taskId = taskId,
        attributes = attributes
    )

fun SecruxPrincipal.projectResource(
    projectId: UUID? = null,
    attributes: Map<String, Any?> = emptyMap()
): AuthorizationResource =
    authResource(
        type = "project",
        resourceId = projectId,
        projectId = projectId,
        attributes = mergeAttributes(attributes, "projectId" to projectId)
    )

fun SecruxPrincipal.repositoryResource(
    projectId: UUID,
    repoId: UUID? = null,
    attributes: Map<String, Any?> = emptyMap()
): AuthorizationResource =
    authResource(
        type = "repository",
        resourceId = repoId,
        projectId = projectId,
        attributes = mergeAttributes(attributes, "projectId" to projectId)
    )

fun SecruxPrincipal.taskResource(
    taskId: UUID? = null,
    projectId: UUID? = null,
    attributes: Map<String, Any?> = emptyMap()
): AuthorizationResource =
    authResource(
        type = "task",
        resourceId = taskId,
        projectId = projectId,
        attributes = mergeAttributes(attributes, "projectId" to projectId)
    )

fun SecruxPrincipal.stageResource(
    taskId: UUID,
    stageId: UUID? = null,
    attributes: Map<String, Any?> = emptyMap()
): AuthorizationResource =
    authResource(
        type = "stage",
        resourceId = stageId,
        taskId = taskId,
        attributes = mergeAttributes(attributes, "taskId" to taskId, "stageId" to stageId)
    )

fun SecruxPrincipal.findingResource(
    taskId: UUID? = null,
    findingId: UUID? = null,
    attributes: Map<String, Any?> = emptyMap()
): AuthorizationResource =
    authResource(
        type = "finding",
        resourceId = findingId,
        taskId = taskId,
        attributes = mergeAttributes(attributes, "taskId" to taskId)
    )

fun SecruxPrincipal.baselineResource(
    projectId: UUID,
    attributes: Map<String, Any?> = emptyMap()
): AuthorizationResource =
    authResource(
        type = "baseline",
        projectId = projectId,
        attributes = mergeAttributes(attributes, "projectId" to projectId)
    )

fun SecruxPrincipal.ticketResource(
    projectId: UUID? = null,
    attributes: Map<String, Any?> = emptyMap()
): AuthorizationResource =
    authResource(
        type = "ticket",
        projectId = projectId,
        attributes = mergeAttributes(attributes, "projectId" to projectId)
    )

fun SecruxPrincipal.ruleResource(
    ruleId: UUID? = null,
    attributes: Map<String, Any?> = emptyMap()
): AuthorizationResource =
    authResource(
        type = "rule",
        resourceId = ruleId,
        attributes = attributes
    )

fun SecruxPrincipal.ruleGroupResource(
    groupId: UUID? = null,
    attributes: Map<String, Any?> = emptyMap()
): AuthorizationResource =
    authResource(
        type = "rule_group",
        resourceId = groupId,
        attributes = attributes
    )

fun SecruxPrincipal.rulesetResource(
    rulesetId: UUID? = null,
    attributes: Map<String, Any?> = emptyMap()
): AuthorizationResource =
    authResource(
        type = "ruleset",
        resourceId = rulesetId,
        attributes = attributes
    )

fun SecruxPrincipal.userResource(
    attributes: Map<String, Any?> = emptyMap()
): AuthorizationResource =
    authResource(
        type = "user",
        attributes = attributes
    )

fun AuthorizationService.requireStage(
    principal: SecruxPrincipal,
    taskId: UUID,
    stageId: UUID,
    action: AuthorizationAction,
    context: Map<String, Any?> = emptyMap()
) {
    require(
        principal = principal,
        action = action,
        resource = principal.stageResource(taskId = taskId, stageId = stageId),
        context = context
    )
}
