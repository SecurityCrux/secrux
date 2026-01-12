package com.securitycrux.secrux.intellij.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.NlsSafe

object SecruxTokenStore {

    fun getToken(project: Project): String? =
        PasswordSafe.instance.getPassword(attributes(project))?.trim()?.ifBlank { null }

    fun setToken(project: Project, token: String?) {
        PasswordSafe.instance.setPassword(attributes(project), token?.trim()?.ifBlank { null })
    }

    fun getTokenAsync(
        project: Project,
        onResult: (String?) -> Unit
    ) {
        val app = ApplicationManager.getApplication()
        app.executeOnPooledThread {
            val token = getToken(project)
            app.invokeLater({ onResult(token) }, ModalityState.any())
        }
    }

    fun setTokenAsync(
        project: Project,
        token: String?,
        onDone: (() -> Unit)? = null
    ) {
        val app = ApplicationManager.getApplication()
        app.executeOnPooledThread {
            setToken(project, token)
            if (onDone != null) {
                app.invokeLater({ onDone() }, ModalityState.any())
            }
        }
    }

    private fun attributes(project: Project): CredentialAttributes {
        val id = project.locationHash
        @NlsSafe val serviceName = "com.securitycrux.secrux.intellij.token.$id"
        return CredentialAttributes(serviceName)
    }
}
