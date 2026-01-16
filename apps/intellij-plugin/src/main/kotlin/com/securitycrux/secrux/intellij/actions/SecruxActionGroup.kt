package com.securitycrux.secrux.intellij.actions

import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.DumbAware
import com.securitycrux.secrux.intellij.i18n.SecruxBundle

class SecruxActionGroup : DefaultActionGroup(), DumbAware {

    init {
        templatePresentation.text = SecruxBundle.message("actionGroup.secrux")
    }
}

