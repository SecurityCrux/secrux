package com.securitycrux.secrux.intellij.secrux

import com.securitycrux.secrux.intellij.i18n.SecruxBundle
import java.util.Locale

enum class SecruxSeverity {
    CRITICAL,
    HIGH,
    MEDIUM,
    LOW,
    INFO;

    override fun toString(): String =
        SecruxBundle.message("severity.${name.lowercase(Locale.ROOT)}")
}
