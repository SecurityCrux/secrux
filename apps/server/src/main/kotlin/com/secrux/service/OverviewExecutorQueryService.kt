package com.secrux.service

import com.secrux.domain.ExecutorStatus
import com.secrux.dto.OverviewExecutorSummary
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class OverviewExecutorQueryService(
    private val dsl: DSLContext
) {

    fun getExecutorSummary(tenantId: UUID): OverviewExecutorSummary {
        val table = DSL.table("executor")
        val tenantField = DSL.field("tenant_id", UUID::class.java)
        val statusField = DSL.field("status", String::class.java)
        val countField = DSL.count().`as`("cnt")
        val records =
            dsl.select(statusField, countField)
                .from(table)
                .where(tenantField.eq(tenantId))
                .groupBy(statusField)
                .fetch()
        val byStatus =
            records.associate { r ->
                val status = ExecutorStatus.valueOf(r.get(statusField))
                val count = (r.get(countField) as? Number)?.toInt() ?: 0
                status to count
            }
        val total = byStatus.values.sum()
        return OverviewExecutorSummary(total = total, byStatus = byStatus)
    }
}

