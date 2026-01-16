package com.secrux.repo

import com.secrux.domain.Baseline
import com.secrux.domain.BaselineKind
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID

@Repository
class BaselineRepository(
    private val dsl: DSLContext
) {

    private val table = DSL.table("baseline")
    private val baselineIdField = DSL.field("baseline_id", UUID::class.java)
    private val tenantField = DSL.field("tenant_id", UUID::class.java)
    private val projectField = DSL.field("project_id", UUID::class.java)
    private val kindField = DSL.field("kind", String::class.java)
    private val fingerprintsField = DSL.field("fingerprints", Array<String>::class.java)
    private val generatedField = DSL.field("generated_at", OffsetDateTime::class.java)

    fun upsert(baseline: Baseline) {
        dsl.deleteFrom(table)
            .where(tenantField.eq(baseline.tenantId))
            .and(projectField.eq(baseline.projectId))
            .and(kindField.eq(baseline.kind.name))
            .execute()

        dsl.insertInto(table)
            .set(baselineIdField, baseline.baselineId)
            .set(tenantField, baseline.tenantId)
            .set(projectField, baseline.projectId)
            .set(kindField, baseline.kind.name)
            .set(fingerprintsField, baseline.fingerprints.toTypedArray())
            .set(generatedField, baseline.generatedAt)
            .execute()
    }

    fun find(tenantId: UUID, projectId: UUID, kind: BaselineKind): Baseline? {
        return dsl.selectFrom(table)
            .where(tenantField.eq(tenantId))
            .and(projectField.eq(projectId))
            .and(kindField.eq(kind.name))
            .fetchOne { mapBaseline(it) }
    }

    fun list(tenantId: UUID, projectId: UUID): List<Baseline> {
        return dsl.selectFrom(table)
            .where(tenantField.eq(tenantId))
            .and(projectField.eq(projectId))
            .orderBy(generatedField.desc())
            .fetch { mapBaseline(it) }
    }

    private fun mapBaseline(record: Record): Baseline {
        val fingerprints = record.get(fingerprintsField)?.toList() ?: emptyList()
        return Baseline(
            baselineId = record.get(baselineIdField),
            tenantId = record.get(tenantField),
            projectId = record.get(projectField),
            kind = BaselineKind.valueOf(record.get(kindField)),
            fingerprints = fingerprints,
            generatedAt = record.get(generatedField)
        )
    }
}

