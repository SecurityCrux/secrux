package com.securitycrux.secrux.intellij.secrux

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import java.awt.Dimension
import javax.swing.JComponent

class AiReviewDialog(
    project: Project,
    title: String,
    private val content: String
) : DialogWrapper(project) {

    init {
        this.title = title
        init()
    }

    override fun createCenterPanel(): JComponent =
        JBScrollPane(
            JBTextArea(content).apply {
                isEditable = false
                caretPosition = 0
            }
        ).apply {
            preferredSize = Dimension(900, 520)
        }
}

