package com.securitycrux.secrux.intellij.secrux

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBUI
import com.securitycrux.secrux.intellij.i18n.SecruxBundle
import com.securitycrux.secrux.intellij.settings.SecruxTokenStore
import java.awt.BorderLayout
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JCheckBox

class ReportToSecruxDialog(
    private val project: Project,
    initialBaseUrl: String,
    initialTaskId: String,
    initialIncludeSnippets: Boolean,
    initialTriggerAiReview: Boolean,
    initialWaitAiReview: Boolean
) : DialogWrapper(project) {

    private val baseUrlField = JBTextField(initialBaseUrl)
    private val taskIdField = JBTextField(initialTaskId)
    private val tokenField = JBPasswordField()
    private val rememberTokenCheckbox = JCheckBox(SecruxBundle.message("dialog.report.rememberToken"), true)
    private val includeSnippetsCheckbox =
        JCheckBox(SecruxBundle.message("dialog.report.includeSnippets"), initialIncludeSnippets)
    private val triggerAiReviewCheckbox =
        JCheckBox(SecruxBundle.message("dialog.report.triggerAiReview"), initialTriggerAiReview)
    private val waitAiReviewCheckbox =
        JCheckBox(SecruxBundle.message("dialog.report.waitAiReview"), initialWaitAiReview).apply {
            isEnabled = initialTriggerAiReview
        }
    private val severityCombo = JComboBox(SecruxSeverity.entries.toTypedArray()).apply {
        selectedItem = SecruxSeverity.HIGH
    }

    val baseUrl: String
        get() = baseUrlField.text.trim()

    val taskId: String
        get() = taskIdField.text.trim()

    val includeSnippets: Boolean
        get() = includeSnippetsCheckbox.isSelected

    val severity: SecruxSeverity
        get() = (severityCombo.selectedItem as? SecruxSeverity) ?: SecruxSeverity.HIGH

    val triggerAiReview: Boolean
        get() = triggerAiReviewCheckbox.isSelected

    val waitAiReview: Boolean
        get() = waitAiReviewCheckbox.isSelected

    val tokenToStore: String?
        get() {
            if (!rememberTokenCheckbox.isSelected) return null
            return String(tokenField.password).trim().ifBlank { null }
        }

    val tokenEntered: String?
        get() = String(tokenField.password).trim().ifBlank { null }

    init {
        title = SecruxBundle.message("dialog.report.title")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        val form = JPanel(VerticalLayout(JBUI.scale(8))).apply {
            border = JBUI.Borders.empty(8)
        }

        triggerAiReviewCheckbox.addActionListener {
            val enabled = triggerAiReviewCheckbox.isSelected
            waitAiReviewCheckbox.isEnabled = enabled
            if (!enabled) {
                waitAiReviewCheckbox.isSelected = false
            }
        }

        form.add(JBLabel(SecruxBundle.message("dialog.report.baseUrl")))
        form.add(baseUrlField)
        form.add(JBLabel(SecruxBundle.message("dialog.report.taskId")))
        form.add(
            JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
                add(taskIdField, BorderLayout.CENTER)
                add(
                    JButton(SecruxBundle.message("action.browseTasks")).apply {
                        addActionListener { event ->
                            val baseUrl = baseUrlField.text.trim()
                            if (baseUrl.isBlank()) {
                                Messages.showErrorDialog(
                                    panel,
                                    SecruxBundle.message("error.baseUrlRequired"),
                                    SecruxBundle.message("dialog.title")
                                )
                                return@addActionListener
                            }

                            fun openSelector(token: String) {
                                val selector =
                                    SelectSecruxTaskDialog(
                                        project = project,
                                        baseUrl = baseUrl,
                                        token = token
                                    )
                                if (!selector.showAndGet()) return

                                selector.selectedTaskId?.let { taskId ->
                                    taskIdField.text = taskId
                                }
                            }

                            val tokenEntered = String(tokenField.password).trim().ifBlank { null }
                            if (!tokenEntered.isNullOrBlank()) {
                                openSelector(tokenEntered)
                                return@addActionListener
                            }

                            val button = event.source as? JButton
                            button?.isEnabled = false
                            SecruxTokenStore.getTokenAsync(project) { token ->
                                button?.isEnabled = true
                                if (token.isNullOrBlank()) {
                                    Messages.showErrorDialog(
                                        panel,
                                        SecruxBundle.message("error.tokenNotSet"),
                                        SecruxBundle.message("dialog.title")
                                    )
                                    return@getTokenAsync
                                }
                                openSelector(token)
                            }
                        }
                    },
                    BorderLayout.EAST
                )
            }
        )
        form.add(JBLabel(SecruxBundle.message("dialog.report.severity")))
        form.add(severityCombo)
        form.add(includeSnippetsCheckbox)
        form.add(triggerAiReviewCheckbox)
        form.add(waitAiReviewCheckbox)
        form.add(JBLabel(SecruxBundle.message("dialog.report.token")))
        form.add(tokenField)
        form.add(rememberTokenCheckbox)

        panel.add(form, BorderLayout.CENTER)
        return panel
    }
}
