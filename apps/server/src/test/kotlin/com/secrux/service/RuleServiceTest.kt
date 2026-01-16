package com.secrux.service

import com.secrux.common.ErrorCode
import com.secrux.common.SecruxException
import com.secrux.domain.Rule
import com.secrux.domain.RuleGroup
import com.secrux.domain.RuleGroupMember
import com.secrux.domain.Ruleset
import com.secrux.domain.RulesetItem
import com.secrux.domain.Severity
import com.secrux.dto.RuleGroupMemberRequest
import com.secrux.dto.RuleGroupRequest
import com.secrux.dto.RuleUpsertRequest
import com.secrux.dto.RulesetPublishRequest
import com.secrux.repo.RuleGroupRepository
import com.secrux.repo.RuleRepository
import com.secrux.repo.RulesetRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.mockito.junit.jupiter.MockitoExtension
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@ExtendWith(MockitoExtension::class)
class RuleServiceTest {

    @Mock
    private lateinit var ruleRepository: RuleRepository

    @Mock
    private lateinit var ruleGroupRepository: RuleGroupRepository

    @Mock
    private lateinit var rulesetRepository: RulesetRepository

    private val clock: Clock = Clock.fixed(Instant.parse("2025-01-01T00:00:00Z"), ZoneOffset.UTC)
    private lateinit var service: RuleService

    @BeforeEach
    fun setup() {
        service = RuleService(ruleRepository, ruleGroupRepository, rulesetRepository, clock)
    }

    @Test
    fun `create rule persists`() {
        val tenantId = UUID.randomUUID()
        val request = RuleUpsertRequest(
            scope = "tenant",
            key = "rule.sql",
            name = "SQL",
            engine = "semgrep",
            langs = listOf("py"),
            severityDefault = Severity.HIGH,
            tags = listOf("sql"),
            pattern = mapOf("pattern" to "select"),
            docs = mapOf("desc" to "sql inject"),
            enabled = true,
            hash = "hash"
        )

        service.createRule(tenantId, request)

        val captor = argumentCaptor<Rule>()
        verify(ruleRepository).insert(captor.capture())
        assertEquals("rule.sql", captor.firstValue.key)
        assertEquals(tenantId, captor.firstValue.tenantId)
    }

    @Test
    fun `publish fails for missing group`() {
        val tenantId = UUID.randomUUID()
        val req = RulesetPublishRequest(groupId = UUID.randomUUID(), profile = "default")
        whenever(ruleGroupRepository.findGroup(req.groupId, tenantId)).thenReturn(null)

        val ex = assertThrows(SecruxException::class.java) {
            service.publishRuleset(tenantId, req)
        }
        assertEquals(ErrorCode.RULE_GROUP_NOT_FOUND, ex.errorCode)
    }

    @Test
    fun `publish ruleset stores items`() {
        val tenantId = UUID.randomUUID()
        val groupId = UUID.randomUUID()
        val ruleId = UUID.randomUUID()
        val group = RuleGroup(
            groupId,
            tenantId,
            "group-key",
            "Group",
            null,
            now().minusDays(1),
            now().minusHours(12),
            null
        )
        whenever(ruleGroupRepository.findGroup(groupId, tenantId)).thenReturn(group)
        val member = RuleGroupMember(UUID.randomUUID(), tenantId, groupId, ruleId, null, Severity.CRITICAL, now())
        whenever(ruleGroupRepository.listMembers(groupId, tenantId)).thenReturn(listOf(member))
        val rule = Rule(
            ruleId = ruleId,
            tenantId = tenantId,
            scope = "tenant",
            key = "rule-1",
            name = "Rule 1",
            engine = "semgrep",
            langs = listOf("py"),
            severityDefault = Severity.MEDIUM,
            tags = listOf("tag"),
            pattern = mapOf("pattern" to "code"),
            docs = null,
            enabled = true,
            hash = "hash-1",
            signature = null,
            createdAt = now(),
            updatedAt = now(),
            deprecatedAt = null
        )
        whenever(ruleRepository.findByIds(tenantId, listOf(ruleId))).thenReturn(listOf(rule))

        service.publishRuleset(tenantId, RulesetPublishRequest(groupId = groupId, profile = "default", version = "1"))

        val rulesetCaptor = argumentCaptor<Ruleset>()
        val itemsCaptor = argumentCaptor<List<RulesetItem>>()
        verify(rulesetRepository).insert(rulesetCaptor.capture(), itemsCaptor.capture())
        assertEquals("default", rulesetCaptor.firstValue.profile)
        assertEquals("group:group-key", rulesetCaptor.firstValue.source)
        assertEquals(1, itemsCaptor.firstValue.size)
        assertEquals(Severity.CRITICAL, itemsCaptor.firstValue.first().severity)
    }

    private fun now() = OffsetDateTime.now(clock)
}
