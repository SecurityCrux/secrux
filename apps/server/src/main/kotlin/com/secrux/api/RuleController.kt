package com.secrux.api

import com.github.xiaoymin.knife4j.annotations.ApiOperationSupport
import com.secrux.common.ApiResponse
import com.secrux.dto.RuleGroupMemberRequest
import com.secrux.dto.RuleGroupRequest
import com.secrux.dto.RuleUpsertRequest
import com.secrux.dto.RulesetPublishRequest
import com.secrux.service.RuleService
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
import org.springframework.web.bind.annotation.RestController
import java.util.UUID
import com.secrux.security.AuthorizationAction
import com.secrux.security.AuthorizationService
import com.secrux.security.SecruxPrincipal
import com.secrux.security.ruleGroupResource
import com.secrux.security.ruleResource
import com.secrux.security.rulesetResource

@RestController
@RequestMapping("/rules")
@Tag(name = "Rule APIs", description = "Rule and ruleset management")
@Validated
class RuleController(
    private val ruleService: RuleService,
    private val authorizationService: AuthorizationService
) {

    @PostMapping
    @Operation(summary = "Create rule", description = "Create security rule")
    @ApiOperationSupport(order = 1)
    fun createRule(
        @AuthenticationPrincipal principal: SecruxPrincipal,
        @Valid @RequestBody request: RuleUpsertRequest
    ): ApiResponse<*> {
        authorizationService.require(
            principal = principal,
            action = AuthorizationAction.RULE_MANAGE,
            resource = principal.ruleResource(),
            context = mapOf(
                "scope" to request.scope,
                "engine" to request.engine
            )
        )
        return ApiResponse(data = ruleService.createRule(principal.tenantId, request))
    }

    @GetMapping
    @Operation(summary = "List rules", description = "List tenant rules")
    @ApiOperationSupport(order = 2)
    fun listRules(
        @AuthenticationPrincipal principal: SecruxPrincipal,
    ): ApiResponse<*> {
        authorizationService.require(
            principal = principal,
            action = AuthorizationAction.RULE_READ,
            resource = principal.ruleResource()
        )
        return ApiResponse(data = ruleService.listRules(principal.tenantId))
    }

    @PutMapping("/{ruleId}")
    @Operation(summary = "Update rule", description = "Update security rule metadata")
    @ApiOperationSupport(order = 3)
    fun updateRule(
        @AuthenticationPrincipal principal: SecruxPrincipal,
        @PathVariable ruleId: UUID,
        @Valid @RequestBody request: RuleUpsertRequest
    ): ApiResponse<*> {
        authorizationService.require(
            principal = principal,
            action = AuthorizationAction.RULE_MANAGE,
            resource = principal.ruleResource(ruleId = ruleId),
            context = mapOf(
                "scope" to request.scope,
                "engine" to request.engine
            )
        )
        return ApiResponse(data = ruleService.updateRule(principal.tenantId, ruleId, request))
    }

    @PostMapping("/groups")
    @Operation(summary = "Create rule group", description = "Create rule group")
    @ApiOperationSupport(order = 4)
    fun createGroup(
        @AuthenticationPrincipal principal: SecruxPrincipal,
        @Valid @RequestBody request: RuleGroupRequest
    ): ApiResponse<*> {
        authorizationService.require(
            principal = principal,
            action = AuthorizationAction.RULE_MANAGE,
            resource = principal.ruleGroupResource()
        )
        return ApiResponse(data = ruleService.createRuleGroup(principal.tenantId, request))
    }

    @PostMapping("/groups/{groupId}/members")
    @Operation(summary = "Add rule to group", description = "Attach rule to group with overrides")
    @ApiOperationSupport(order = 5)
    fun addMember(
        @AuthenticationPrincipal principal: SecruxPrincipal,
        @PathVariable groupId: UUID,
        @Valid @RequestBody request: RuleGroupMemberRequest
    ): ApiResponse<*> {
        authorizationService.require(
            principal = principal,
            action = AuthorizationAction.RULE_MANAGE,
            resource = principal.ruleGroupResource(groupId = groupId)
        )
        return ApiResponse(data = ruleService.addRuleToGroup(principal.tenantId, groupId, request))
    }

    @DeleteMapping("/groups/{groupId}/members/{memberId}")
    @Operation(summary = "Delete rule from group", description = "Remove rule member from group")
    @ApiOperationSupport(order = 6)
    fun deleteMember(
        @AuthenticationPrincipal principal: SecruxPrincipal,
        @PathVariable groupId: UUID,
        @PathVariable memberId: UUID
    ): ApiResponse<*> {
        authorizationService.require(
            principal = principal,
            action = AuthorizationAction.RULE_MANAGE,
            resource = principal.ruleGroupResource(groupId = groupId)
        )
        ruleService.deleteRuleFromGroup(principal.tenantId, groupId, memberId)
        return ApiResponse<Unit>(message = "Rule removed from group")
    }

    @PostMapping("/rulesets/publish")
    @Operation(summary = "Publish ruleset", description = "Publish ruleset from group")
    @ApiOperationSupport(order = 7)
    fun publishRuleset(
        @AuthenticationPrincipal principal: SecruxPrincipal,
        @Valid @RequestBody request: RulesetPublishRequest
    ): ApiResponse<*> {
        authorizationService.require(
            principal = principal,
            action = AuthorizationAction.RULESET_PUBLISH,
            resource = principal.ruleGroupResource(groupId = request.groupId),
            context = mapOf("profile" to request.profile)
        )
        return ApiResponse(data = ruleService.publishRuleset(principal.tenantId, request))
    }

    @DeleteMapping("/groups/{groupId}")
    @Operation(summary = "Delete rule group", description = "Soft delete rule group")
    @ApiOperationSupport(order = 8)
    fun deleteGroup(
        @AuthenticationPrincipal principal: SecruxPrincipal,
        @PathVariable groupId: UUID
    ): ApiResponse<*> {
        authorizationService.require(
            principal = principal,
            action = AuthorizationAction.RULE_MANAGE,
            resource = principal.ruleGroupResource(groupId = groupId)
        )
        ruleService.deleteRuleGroup(principal.tenantId, groupId)
        return ApiResponse<Unit>(message = "Rule group deleted")
    }

    @DeleteMapping("/rulesets/{rulesetId}")
    @Operation(summary = "Delete ruleset", description = "Soft delete published ruleset")
    @ApiOperationSupport(order = 9)
    fun deleteRuleset(
        @AuthenticationPrincipal principal: SecruxPrincipal,
        @PathVariable rulesetId: UUID
    ): ApiResponse<*> {
        authorizationService.require(
            principal = principal,
            action = AuthorizationAction.RULESET_PUBLISH,
            resource = principal.rulesetResource(rulesetId = rulesetId)
        )
        ruleService.deleteRuleset(principal.tenantId, rulesetId)
        return ApiResponse<Unit>(message = "Ruleset deleted")
    }
}

