package com.securitycrux.secrux.intellij.sinks

import com.intellij.openapi.vfs.VirtualFile
import org.skgroup.codeauditassistant.enums.SubVulnerabilityType

data class SinkMatch(
    val type: SubVulnerabilityType,
    val targetClassFqn: String,
    val targetMember: String,
    val targetParamCount: Int,
    val file: VirtualFile,
    val startOffset: Int,
    val endOffset: Int,
    val line: Int,
    val column: Int,
    val enclosingMethodFqn: String?
)
