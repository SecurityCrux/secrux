package com.secrux.repo

import com.secrux.domain.LogLevel
import com.secrux.domain.LogStream
import com.secrux.domain.StageType
import com.secrux.domain.TaskLogChunk
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID

@Repository
class TaskLogRepository(
    private val dsl: DSLContext
) {

    private val table = DSL.table("task_log_chunk")
    private val chunkIdField = DSL.field("chunk_id", UUID::class.java)
    private val taskIdField = DSL.field("task_id", UUID::class.java)
    private val sequenceField = DSL.field("sequence", Long::class.javaObjectType)
    private val streamField = DSL.field("stream", String::class.java)
    private val contentField = DSL.field("content", String::class.java)
    private val createdAtField = DSL.field("created_at", OffsetDateTime::class.java)
    private val isLastField = DSL.field("is_last", Boolean::class.javaObjectType)
    private val stageIdField = DSL.field("stage_id", UUID::class.java)
    private val stageTypeField = DSL.field("stage_type", String::class.java)
    private val levelField = DSL.field("log_level", String::class.java)

    fun insert(chunk: TaskLogChunk) {
        dsl.insertInto(table)
            .set(chunkIdField, chunk.chunkId)
            .set(taskIdField, chunk.taskId)
            .set(sequenceField, chunk.sequence)
            .set(streamField, chunk.stream.name.lowercase())
            .set(contentField, chunk.content)
            .set(createdAtField, chunk.createdAt)
            .set(isLastField, chunk.isLast)
            .set(stageIdField, chunk.stageId)
            .set(stageTypeField, chunk.stageType?.name)
            .set(levelField, chunk.level.name)
            .execute()
    }

    fun list(taskId: UUID, afterSequence: Long?, limit: Int): List<TaskLogChunk> {
        var condition = taskIdField.eq(taskId)
        afterSequence?.let {
            condition = condition.and(sequenceField.gt(it))
        }
        return dsl.selectFrom(table)
            .where(condition)
            .orderBy(sequenceField.asc())
            .limit(limit)
            .fetch { mapChunk(it) }
    }

    fun deleteByTask(taskId: UUID) {
        dsl.deleteFrom(table)
            .where(taskIdField.eq(taskId))
            .execute()
    }

    fun deleteOlderThan(cutoff: OffsetDateTime) {
        dsl.deleteFrom(table)
            .where(createdAtField.lt(cutoff))
            .execute()
    }

    fun hasLogs(taskId: UUID): Boolean =
        dsl.fetchExists(
            DSL.selectOne()
                .from(table)
                .where(taskIdField.eq(taskId))
        )

    fun nextSequence(taskId: UUID): Long =
        dsl.select(DSL.max(sequenceField))
            .from(table)
            .where(taskIdField.eq(taskId))
            .fetchOne(0, Long::class.javaObjectType)
            ?.let { it + 1 } ?: 0L

    private fun mapChunk(record: Record): TaskLogChunk =
        TaskLogChunk(
            chunkId = record.get(chunkIdField),
            taskId = record.get(taskIdField),
            sequence = record.get(sequenceField)?.toLong() ?: 0,
            stream = when (record.get(streamField)?.uppercase()) {
                "STDERR" -> LogStream.STDERR
                else -> LogStream.STDOUT
            },
            content = record.get(contentField) ?: "",
            isLast = record.get(isLastField) ?: false,
            createdAt = record.get(createdAtField),
            stageId = record.get(stageIdField),
            stageType = record.get(stageTypeField)?.let { StageType.valueOf(it) },
            level = record.get(levelField)?.let { LogLevel.valueOf(it) } ?: LogLevel.INFO
        )

    fun listBetween(
        taskId: UUID,
        fromInclusive: OffsetDateTime?,
        toInclusive: OffsetDateTime?,
        limit: Int
    ): List<TaskLogChunk> {
        var condition = taskIdField.eq(taskId)
        fromInclusive?.let {
            condition = condition.and(createdAtField.greaterOrEqual(it))
        }
        toInclusive?.let {
            condition = condition.and(createdAtField.lessOrEqual(it))
        }
        return dsl.selectFrom(table)
            .where(condition)
            .orderBy(sequenceField.asc())
            .limit(limit)
            .fetch { mapChunk(it) }
    }

    fun listForStage(stageId: UUID, limit: Int): List<TaskLogChunk> =
        dsl.selectFrom(table)
            .where(stageIdField.eq(stageId))
            .orderBy(sequenceField.asc())
            .limit(limit)
            .fetch { mapChunk(it) }
}
