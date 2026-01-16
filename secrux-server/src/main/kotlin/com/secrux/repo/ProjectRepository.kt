package com.secrux.repo

import com.secrux.domain.Project
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID

@Repository
class ProjectRepository(
    private val dsl: DSLContext
) {

    private val table = DSL.table("project")
    private val projectField = DSL.field("project_id", UUID::class.java)
    private val tenantField = DSL.field("tenant_id", UUID::class.java)
    private val nameField = DSL.field("name", String::class.java)
    private val codeOwnersField = DSL.field("code_owners", Array<String>::class.java)
    private val createdAtField = DSL.field("created_at", OffsetDateTime::class.java)
    private val updatedAtField = DSL.field("updated_at", OffsetDateTime::class.java)
    private val deletedAtField = DSL.field("deleted_at", OffsetDateTime::class.java)

    fun exists(tenantId: UUID, projectId: UUID): Boolean {
        return dsl.fetchExists(
            dsl.selectOne()
                .from(table)
                .where(tenantField.eq(tenantId))
            .and(projectField.eq(projectId))
            .and(deletedAtField.isNull)
        )
    }

    fun insert(project: Project) {
        dsl.insertInto(table)
            .set(projectField, project.projectId)
            .set(tenantField, project.tenantId)
            .set(nameField, project.name)
            .set(codeOwnersField, project.codeOwners.toTypedArray())
            .set(createdAtField, project.createdAt)
            .set(updatedAtField, project.updatedAt)
            .set(deletedAtField, project.deletedAt)
            .execute()
    }

    fun update(project: Project) {
        dsl.update(table)
            .set(nameField, project.name)
            .set(codeOwnersField, project.codeOwners.toTypedArray())
            .set(updatedAtField, project.updatedAt)
            .where(projectField.eq(project.projectId))
            .and(tenantField.eq(project.tenantId))
            .and(deletedAtField.isNull)
            .execute()
    }

    fun findById(projectId: UUID, tenantId: UUID): Project? {
        return dsl.selectFrom(table)
            .where(projectField.eq(projectId))
            .and(tenantField.eq(tenantId))
            .and(deletedAtField.isNull)
            .fetchOne { mapProject(it) }
    }

    fun list(tenantId: UUID): List<Project> {
        return dsl.selectFrom(table)
            .where(tenantField.eq(tenantId))
            .and(deletedAtField.isNull)
            .orderBy(createdAtField.desc())
            .fetch { mapProject(it) }
    }

    private fun mapProject(record: Record): Project {
        val codeOwners = record.get(codeOwnersField)?.toList() ?: emptyList()
        return Project(
            projectId = record.get(projectField),
            tenantId = record.get(tenantField),
            name = record.get(nameField),
            codeOwners = codeOwners,
            createdAt = record.get(createdAtField),
            updatedAt = record.get(updatedAtField),
            deletedAt = record.get(deletedAtField)
        )
    }

    fun softDelete(projectId: UUID, tenantId: UUID, deletedAt: OffsetDateTime): Int {
        return dsl.update(table)
            .set(deletedAtField, deletedAt)
            .where(projectField.eq(projectId))
            .and(tenantField.eq(tenantId))
            .and(deletedAtField.isNull)
            .execute()
    }
}

