package com.securitycrux.secrux.intellij.valueflow

import com.securitycrux.secrux.intellij.callgraph.MethodRef

data class FieldStore(
    val targetField: String,
    val value: String,
    val offset: Int?,
)

data class FieldLoad(
    val target: String,
    val sourceField: String,
    val offset: Int?,
)

data class CallSiteSummary(
    val calleeId: String?,
    val callOffset: Int?,
    val receiver: String?,
    val args: List<String>,
    val result: String?,
)

data class MethodSummary(
    val fieldsRead: Set<String> = emptySet(),
    val fieldsWritten: Set<String> = emptySet(),
    val stores: List<FieldStore> = emptyList(),
    val loads: List<FieldLoad> = emptyList(),
    val calls: List<CallSiteSummary> = emptyList(),
)

data class MethodSummaryStats(
    val methodsIndexed: Int,
    val methodsWithFieldAccess: Int,
    val fieldReads: Int,
    val fieldWrites: Int,
    val distinctFields: Int,
)

data class MethodSummaryIndex(
    val summaries: Map<MethodRef, MethodSummary>,
    val stats: MethodSummaryStats,
)
