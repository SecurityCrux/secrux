package com.secrux.service.enrichment

internal fun normalizeJavaSymbol(symbol: String): String {
    val s = symbol.trim()
    if (s.startsWith("this.")) return s.removePrefix("this.")
    val dot = s.lastIndexOf('.')
    return if (dot >= 0) s.substring(dot + 1) else s
}

