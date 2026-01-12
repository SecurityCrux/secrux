package com.securitycrux.secrux.intellij.memo

data class AuditMemoItemState(
    var id: String = "",
    var title: String = "",
    var filePath: String = "",
    var line: Int = 0,
    var column: Int = 0,
    var offset: Int = 0,
    var createdAtEpochMs: Long = 0
)

