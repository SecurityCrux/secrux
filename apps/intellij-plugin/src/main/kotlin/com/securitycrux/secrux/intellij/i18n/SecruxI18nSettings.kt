package com.securitycrux.secrux.intellij.i18n

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

data class SecruxI18nSettingsState(
    var languageMode: SecruxLanguageMode = SecruxLanguageMode.IDE
)

@Service(Service.Level.APP)
@State(
    name = "SecruxI18nSettings",
    storages = [Storage("secrux-i18n.xml")]
)
class SecruxI18nSettings : PersistentStateComponent<SecruxI18nSettingsState> {

    private var state = SecruxI18nSettingsState()

    override fun getState(): SecruxI18nSettingsState = state

    override fun loadState(state: SecruxI18nSettingsState) {
        this.state = state
    }

    fun setLanguageMode(mode: SecruxLanguageMode) {
        if (state.languageMode == mode) return
        state.languageMode = mode
        ApplicationManager.getApplication()
            .messageBus
            .syncPublisher(SecruxI18nListener.TOPIC)
            .onLanguageModeChanged(mode)
    }

    companion object {
        fun getInstance(): SecruxI18nSettings = service()
    }
}

