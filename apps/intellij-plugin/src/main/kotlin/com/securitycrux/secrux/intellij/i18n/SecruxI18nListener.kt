package com.securitycrux.secrux.intellij.i18n

import com.intellij.util.messages.Topic

fun interface SecruxI18nListener {

    fun onLanguageModeChanged(mode: SecruxLanguageMode)

    companion object {
        val TOPIC: Topic<SecruxI18nListener> =
            Topic.create("Secrux I18n Updates", SecruxI18nListener::class.java)
    }
}

