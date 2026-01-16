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
import java.awt.BorderLayout
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel

class CreateSecruxRepositoryDialog(
    project: Project,
    initialRemoteUrl: String? = null,
    initialDefaultBranch: String? = null,
    initialScmType: String? = null,
) : DialogWrapper(project) {

    enum class GitAuthModeOption(val apiValue: String) {
        NONE("NONE"),
        BASIC("BASIC"),
        TOKEN("TOKEN");

        override fun toString(): String =
            when (this) {
                NONE -> SecruxBundle.message("combo.gitAuth.none")
                BASIC -> SecruxBundle.message("combo.gitAuth.basic")
                TOKEN -> SecruxBundle.message("combo.gitAuth.token")
            }
    }

    private val remoteUrlField = JBTextField(initialRemoteUrl.orEmpty())
    private val defaultBranchField = JBTextField(initialDefaultBranch.orEmpty())
    private val scmTypeField = JBTextField(initialScmType.orEmpty())

    private val authModeCombo = JComboBox(GitAuthModeOption.entries.toTypedArray()).apply {
        selectedItem = GitAuthModeOption.NONE
    }
    private val usernameField = JBTextField()
    private val passwordField = JBPasswordField()
    private val tokenField = JBPasswordField()

    val remoteUrl: String
        get() = remoteUrlField.text.trim()

    val defaultBranch: String?
        get() = defaultBranchField.text.trim().ifBlank { null }

    val scmType: String?
        get() = scmTypeField.text.trim().ifBlank { null }

    val gitAuthMode: GitAuthModeOption
        get() = (authModeCombo.selectedItem as? GitAuthModeOption) ?: GitAuthModeOption.NONE

    val gitUsername: String?
        get() = usernameField.text.trim().ifBlank { null }

    val gitPassword: String?
        get() = String(passwordField.password).trim().ifBlank { null }

    val gitToken: String?
        get() = String(tokenField.password).trim().ifBlank { null }

    init {
        title = SecruxBundle.message("dialog.createRepository.title")

        authModeCombo.addActionListener { updateAuthFields() }
        updateAuthFields()

        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        val form =
            JPanel(VerticalLayout(JBUI.scale(8))).apply {
                border = JBUI.Borders.empty(8)
            }

        form.add(JBLabel(SecruxBundle.message("dialog.createRepository.remoteUrl")))
        form.add(remoteUrlField)
        form.add(JBLabel(SecruxBundle.message("dialog.createRepository.defaultBranch")))
        form.add(defaultBranchField)
        form.add(JBLabel(SecruxBundle.message("dialog.createRepository.scmType")))
        form.add(scmTypeField)

        form.add(JBLabel(SecruxBundle.message("dialog.createRepository.gitAuthMode")))
        form.add(authModeCombo)
        form.add(JBLabel(SecruxBundle.message("dialog.createRepository.gitUsername")))
        form.add(usernameField)
        form.add(JBLabel(SecruxBundle.message("dialog.createRepository.gitPassword")))
        form.add(passwordField)
        form.add(JBLabel(SecruxBundle.message("dialog.createRepository.gitToken")))
        form.add(tokenField)

        panel.add(form, BorderLayout.CENTER)
        return panel
    }

    override fun doOKAction() {
        if (remoteUrl.isBlank()) {
            Messages.showErrorDialog(
                SecruxBundle.message("error.repositoryRemoteUrlRequired"),
                SecruxBundle.message("dialog.title"),
            )
            return
        }

        when (gitAuthMode) {
            GitAuthModeOption.NONE -> Unit

            GitAuthModeOption.BASIC -> {
                if (gitUsername.isNullOrBlank()) {
                    Messages.showErrorDialog(
                        SecruxBundle.message("error.gitAuthUsernameRequired"),
                        SecruxBundle.message("dialog.title"),
                    )
                    return
                }
                if (gitPassword.isNullOrBlank()) {
                    Messages.showErrorDialog(
                        SecruxBundle.message("error.gitAuthPasswordRequired"),
                        SecruxBundle.message("dialog.title"),
                    )
                    return
                }
            }

            GitAuthModeOption.TOKEN -> {
                if (gitToken.isNullOrBlank()) {
                    Messages.showErrorDialog(
                        SecruxBundle.message("error.gitAuthTokenRequired"),
                        SecruxBundle.message("dialog.title"),
                    )
                    return
                }
            }
        }

        super.doOKAction()
    }

    private fun updateAuthFields() {
        when (gitAuthMode) {
            GitAuthModeOption.NONE -> {
                usernameField.isEnabled = false
                passwordField.isEnabled = false
                tokenField.isEnabled = false
            }

            GitAuthModeOption.BASIC -> {
                usernameField.isEnabled = true
                passwordField.isEnabled = true
                tokenField.isEnabled = false
            }

            GitAuthModeOption.TOKEN -> {
                usernameField.isEnabled = true
                passwordField.isEnabled = false
                tokenField.isEnabled = true
            }
        }
    }
}

