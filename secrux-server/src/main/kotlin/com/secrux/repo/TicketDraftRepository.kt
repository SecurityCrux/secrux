package com.secrux.repo

import com.fasterxml.jackson.databind.ObjectMapper
import com.secrux.domain.TicketDraftItemRef
import com.secrux.domain.TicketDraftItemType
import com.secrux.domain.TicketDraft
import com.secrux.domain.TicketDraftStatus
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.Record
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID

@Repository
class TicketDraftRepository(
    private val dsl: DSLContext,
    private val objectMapper: ObjectMapper
) {

    private val draftTable = DSL.table("ticket_draft")
    private val draftIdField = DSL.field("draft_id", UUID::class.java)
    private val tenantField = DSL.field("tenant_id", UUID::class.java)
    private val userField = DSL.field("user_id", UUID::class.java)
    private val projectField = DSL.field("project_id", UUID::class.java)
    private val providerField = DSL.field("provider", String::class.java)
    private val titleI18nField = DSL.field("title_i18n", JSONB::class.java)
    private val descriptionI18nField = DSL.field("description_i18n", JSONB::class.java)
    private val statusField = DSL.field("status", String::class.java)
    private val lastAiJobIdField = DSL.field("last_ai_job_id", String::class.java)
    private val createdField = DSL.field("created_at", OffsetDateTime::class.java)
    private val updatedField = DSL.field("updated_at", OffsetDateTime::class.java)
    private val deletedField = DSL.field("deleted_at", OffsetDateTime::class.java)

    private val itemTable = DSL.table("ticket_draft_item")
    private val itemDraftIdField = DSL.field("draft_id", UUID::class.java)
    private val itemTenantField = DSL.field("tenant_id", UUID::class.java)
    private val itemTypeField = DSL.field("item_type", String::class.java)
    private val itemIdField = DSL.field("item_id", UUID::class.java)
    private val itemAddedAtField = DSL.field("added_at", OffsetDateTime::class.java)

    fun findCurrent(tenantId: UUID, userId: UUID): TicketDraft? =
        dsl.selectFrom(draftTable)
            .where(tenantField.eq(tenantId))
            .and(userField.eq(userId))
            .and(statusField.eq(TicketDraftStatus.DRAFT.name))
            .and(deletedField.isNull)
            .fetchOne { mapDraft(it) }

    fun insert(draft: TicketDraft) {
        dsl.insertInto(draftTable)
            .set(draftIdField, draft.draftId)
            .set(tenantField, draft.tenantId)
            .set(userField, draft.userId)
            .set(projectField, draft.projectId)
            .set(providerField, draft.provider)
            .set(titleI18nField, objectMapper.toJsonbOrNull(draft.titleI18n))
            .set(descriptionI18nField, objectMapper.toJsonbOrNull(draft.descriptionI18n))
            .set(statusField, draft.status.name)
            .set(lastAiJobIdField, draft.lastAiJobId)
            .set(createdField, draft.createdAt)
            .set(updatedField, draft.updatedAt)
            .execute()
    }

    fun updateDraft(
        draftId: UUID,
        tenantId: UUID,
        projectId: UUID?,
        provider: String?,
        titleI18n: Map<String, Any?>?,
        descriptionI18n: Map<String, Any?>?,
        lastAiJobId: String?,
        status: TicketDraftStatus?,
        updatedAt: OffsetDateTime
    ) {
        var update = dsl.update(draftTable)
            .set(updatedField, updatedAt)

        projectId?.let { update = update.set(projectField, it) }
        update = update.set(providerField, provider)
        update = update.set(titleI18nField, objectMapper.toJsonbOrNull(titleI18n))
        update = update.set(descriptionI18nField, objectMapper.toJsonbOrNull(descriptionI18n))
        update = update.set(lastAiJobIdField, lastAiJobId)
        status?.let { update = update.set(statusField, it.name) }

        update.where(draftIdField.eq(draftId))
            .and(tenantField.eq(tenantId))
            .and(deletedField.isNull)
            .execute()
    }

    fun clearItems(draftId: UUID, tenantId: UUID) {
        dsl.deleteFrom(itemTable)
            .where(itemDraftIdField.eq(draftId))
            .and(itemTenantField.eq(tenantId))
            .execute()
    }

    fun addItems(draftId: UUID, tenantId: UUID, items: List<TicketDraftItemRef>, addedAt: OffsetDateTime) {
        val queries =
            items.distinctBy { it.type to it.id }.map { item ->
                dsl.insertInto(itemTable)
                    .columns(itemDraftIdField, itemTenantField, itemTypeField, itemIdField, itemAddedAtField)
                    .values(draftId, tenantId, item.type.name, item.id, addedAt)
                    .onConflict(itemDraftIdField, itemTypeField, itemIdField)
                    .doNothing()
            }
        if (queries.isNotEmpty()) {
            dsl.batch(queries).execute()
        }
    }

    fun removeItems(draftId: UUID, tenantId: UUID, items: List<TicketDraftItemRef>) {
        val unique = items.distinctBy { it.type to it.id }
        if (unique.isEmpty()) return
        val rows = unique.map { DSL.row(it.type.name, it.id) }
        dsl.deleteFrom(itemTable)
            .where(itemDraftIdField.eq(draftId))
            .and(itemTenantField.eq(tenantId))
            .and(DSL.row(itemTypeField, itemIdField).`in`(rows))
            .execute()
    }

    fun listItems(draftId: UUID, tenantId: UUID): List<TicketDraftItemRef> =
        dsl.select(itemTypeField, itemIdField)
            .from(itemTable)
            .where(itemDraftIdField.eq(draftId))
            .and(itemTenantField.eq(tenantId))
            .orderBy(itemAddedAtField.asc())
            .fetch { record ->
                val typeName = record.get(itemTypeField)
                val type = TicketDraftItemType.valueOf(typeName)
                TicketDraftItemRef(
                    type = type,
                    id = record.get(itemIdField)
                )
            }

    fun markSubmitted(draftId: UUID, tenantId: UUID, submittedAt: OffsetDateTime) {
        dsl.update(draftTable)
            .set(statusField, TicketDraftStatus.SUBMITTED.name)
            .set(updatedField, submittedAt)
            .where(draftIdField.eq(draftId))
            .and(tenantField.eq(tenantId))
            .and(deletedField.isNull)
            .execute()
    }

    fun resetDraft(draftId: UUID, tenantId: UUID, updatedAt: OffsetDateTime) {
        dsl.update(draftTable)
            .set(projectField, null as UUID?)
            .set(providerField, null as String?)
            .set(titleI18nField, null as JSONB?)
            .set(descriptionI18nField, null as JSONB?)
            .set(lastAiJobIdField, null as String?)
            .set(updatedField, updatedAt)
            .where(draftIdField.eq(draftId))
            .and(tenantField.eq(tenantId))
            .and(deletedField.isNull)
            .execute()
    }

    private fun mapDraft(record: Record): TicketDraft {
        val titleRaw = record.get(titleI18nField)?.data()
        val descRaw = record.get(descriptionI18nField)?.data()
        val title = titleRaw?.let { objectMapper.readMapOrEmpty(it) }
        val desc = descRaw?.let { objectMapper.readMapOrEmpty(it) }
        return TicketDraft(
            draftId = record.get(draftIdField),
            tenantId = record.get(tenantField),
            userId = record.get(userField),
            projectId = record.get(projectField),
            provider = record.get(providerField),
            titleI18n = title,
            descriptionI18n = desc,
            status = TicketDraftStatus.valueOf(record.get(statusField)),
            lastAiJobId = record.get(lastAiJobIdField),
            createdAt = record.get(createdField),
            updatedAt = record.get(updatedField)
        )
    }
}
