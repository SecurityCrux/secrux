package com.securitycrux.secrux.intellij.i18n

import com.intellij.DynamicBundle
import com.intellij.openapi.application.ApplicationManager
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.PropertyKey
import java.text.MessageFormat
import java.util.Locale
import java.util.ResourceBundle
import java.util.concurrent.ConcurrentHashMap

private const val BUNDLE = "messages.SecruxBundle"

private object SecruxIdeBundle : DynamicBundle(BUNDLE)

object SecruxBundle {

    private val bundles = ConcurrentHashMap<Locale, ResourceBundle>()

    @JvmStatic
    @Nls
    fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any): String {
        return when (languageMode()) {
            SecruxLanguageMode.IDE -> runCatching { SecruxIdeBundle.getMessage(key, *params) }.getOrElse { key }
            SecruxLanguageMode.EN -> messageForLocale(Locale.ENGLISH, key, params)
            SecruxLanguageMode.ZH_CN -> messageForLocale(Locale.SIMPLIFIED_CHINESE, key, params)
        }
    }

    private fun messageForLocale(locale: Locale, key: String, params: Array<out Any>): String {
        val bundle =
            bundles.computeIfAbsent(locale) {
                ResourceBundle.getBundle(BUNDLE, locale, SecruxBundle::class.java.classLoader)
            }
        val pattern = runCatching { bundle.getString(key) }.getOrElse { return key }
        return MessageFormat(pattern, locale).format(params)
    }

    private fun languageMode(): SecruxLanguageMode =
        runCatching {
            ApplicationManager.getApplication()
                ?.getService(SecruxI18nSettings::class.java)
                ?.state
                ?.languageMode
        }.getOrNull() ?: SecruxLanguageMode.IDE
}
