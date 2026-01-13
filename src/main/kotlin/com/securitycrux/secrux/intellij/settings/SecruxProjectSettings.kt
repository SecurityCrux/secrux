package com.securitycrux.secrux.intellij.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil
import com.securitycrux.secrux.intellij.memo.AuditMemoItemState

data class SecruxProjectSettingsState(
    var baseUrl: String = "http://localhost:8080",
    var taskId: String = "",
    var includeSnippetsOnReport: Boolean = true,
    var includeEnrichmentOnReport: Boolean = false,
    var triggerAiReviewOnReport: Boolean = false,
    var waitAiReviewOnReport: Boolean = false,
    var memoItems: MutableList<AuditMemoItemState> = mutableListOf(),
    var excludedPathRegex: String = "",
    var excludedSinkTypes: MutableList<String> = mutableListOf(),
    var sinkCatalogId: String = "builtin_all",
    var sinkCatalogFilePath: String = ""
)

@Service(Service.Level.PROJECT)
@State(
    name = "SecruxIntellijPluginSettings",
    storages = [Storage(StoragePathMacros.WORKSPACE_FILE)]
)
class SecruxProjectSettings(
    private val project: Project
) : PersistentStateComponent<SecruxProjectSettingsState> {

    private var state = SecruxProjectSettingsState()

    override fun getState(): SecruxProjectSettingsState = state

    override fun loadState(state: SecruxProjectSettingsState) {
        XmlSerializerUtil.copyBean(state, this.state)
    }

    companion object {
        fun getInstance(project: Project): SecruxProjectSettings = project.service()
    }
}
