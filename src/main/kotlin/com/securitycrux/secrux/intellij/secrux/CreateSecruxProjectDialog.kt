package com.securitycrux.secrux.intellij.secrux

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBUI
import com.securitycrux.secrux.intellij.i18n.SecruxBundle
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JPanel

class CreateSecruxProjectDialog(
    project: Project,
    initialProjectName: String,
    initialCodeOwners: List<String> = emptyList(),
) : DialogWrapper(project) {

    private val projectNameField = JBTextField(initialProjectName)
    private val codeOwnersArea =
        JBTextArea(initialCodeOwners.joinToString("\n")).apply {
            lineWrap = true
            wrapStyleWord = true
        }

    val projectName: String
        get() = projectNameField.text.trim()

    val codeOwners: List<String>
        get() =
            codeOwnersArea.text
                .split('\n', '\r', ',', ';')
                .asSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .toList()

    init {
        title = SecruxBundle.message("dialog.createProject.title")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        val form =
            JPanel(VerticalLayout(JBUI.scale(8))).apply {
                border = JBUI.Borders.empty(8)
            }

        form.add(JBLabel(SecruxBundle.message("dialog.createProject.name")))
        form.add(projectNameField)
        form.add(JBLabel(SecruxBundle.message("dialog.createProject.codeOwners")))
        form.add(
            JBScrollPane(codeOwnersArea).apply {
                preferredSize = Dimension(420, 120)
            },
        )

        panel.add(form, BorderLayout.CENTER)
        return panel
    }

    override fun doOKAction() {
        if (projectName.isBlank()) {
            Messages.showErrorDialog(
                SecruxBundle.message("error.projectNameRequired"),
                SecruxBundle.message("dialog.title"),
            )
            return
        }
        super.doOKAction()
    }
}

