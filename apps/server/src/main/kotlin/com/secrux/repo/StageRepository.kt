package com.secrux.repo

import com.fasterxml.jackson.databind.ObjectMapper
import com.secrux.domain.Stage
import com.secrux.domain.StageMetrics
import com.secrux.domain.StageSignals
import com.secrux.domain.StageSpec
import com.secrux.domain.StageStatus
import com.secrux.domain.StageType
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.Record
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class StageRepository(
    private val dsl: DSLContext,
    private val objectMapper: ObjectMapper
) {

    private val stageTable = DSL.table("stage")
    private val stageIdField = DSL.field("stage_id", UUID::class.java)
    private val tenantIdField = DSL.field("tenant_id", UUID::class.java)
    private val taskIdField = DSL.field("task_id", UUID::class.java)
    private val typeField = DSL.field("type", String::class.java)
    private val specField = DSL.field("spec", JSONB::class.java)
    private val statusField = DSL.field("status", String::class.java)
    private val metricsField = DSL.field("metrics", JSONB::class.java)
    private val signalsField = DSL.field("signals", JSONB::class.java)
    private val artifactsField = DSL.field("artifacts_uri", Array<String>::class.java)
    private val startedAtField = DSL.field("started_at", java.time.OffsetDateTime::class.java)
    private val endedAtField = DSL.field("ended_at", java.time.OffsetDateTime::class.java)

    fun upsert(stage: Stage) {
        dsl.insertInto(stageTable)
            .set(stageIdField, stage.stageId)
            .set(tenantIdField, stage.tenantId)
            .set(taskIdField, stage.taskId)
            .set(typeField, stage.type.name)
            .set(specField, objectMapper.toJsonb(stage.spec))
            .set(statusField, stage.status.name)
            .set(metricsField, objectMapper.toJsonb(stage.metrics))
            .set(signalsField, objectMapper.toJsonb(stage.signals))
            .set(artifactsField, stage.artifacts.toTypedArray())
            .set(startedAtField, stage.startedAt)
            .set(endedAtField, stage.endedAt)
            .onConflict(stageIdField)
            .doUpdate()
            .set(specField, objectMapper.toJsonb(stage.spec))
            .set(statusField, stage.status.name)
            .set(metricsField, objectMapper.toJsonb(stage.metrics))
            .set(signalsField, objectMapper.toJsonb(stage.signals))
            .set(artifactsField, stage.artifacts.toTypedArray())
            .set(startedAtField, stage.startedAt)
            .set(endedAtField, stage.endedAt)
            .execute()
    }

    fun findById(stageId: UUID, tenantId: UUID): Stage? {
        return dsl.selectFrom(stageTable)
            .where(stageIdField.eq(stageId))
            .and(tenantIdField.eq(tenantId))
            .fetchOne { mapStage(it) }
    }

    fun listByTask(taskId: UUID, tenantId: UUID): List<Stage> {
        return dsl.selectFrom(stageTable)
            .where(taskIdField.eq(taskId))
            .and(tenantIdField.eq(tenantId))
            .orderBy(startedAtField.asc())
            .fetch { mapStage(it) }
    }

    private fun mapStage(record: Record): Stage {
        val specJson = record.get(specField)
        val metricsJson = record.get(metricsField)
        val signalsJson = record.get(signalsField)
        val spec = objectMapper.readValue(specJson.data(), StageSpec::class.java)
        val metrics = objectMapper.readValue(metricsJson.data(), StageMetrics::class.java)
        val signals = objectMapper.readValue(signalsJson.data(), StageSignals::class.java)
        return Stage(
            stageId = record.get(stageIdField),
            tenantId = record.get(tenantIdField),
            taskId = record.get(taskIdField),
            type = StageType.valueOf(record.get(typeField)),
            spec = spec,
            status = StageStatus.valueOf(record.get(statusField)),
            metrics = metrics,
            signals = signals,
            artifacts = record.get(artifactsField)?.toList() ?: emptyList(),
            startedAt = record.get(startedAtField),
            endedAt = record.get(endedAtField),
        )
    }
}
