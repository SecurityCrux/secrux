package com.securitycrux.secrux.intellij.toolwindow

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.securitycrux.secrux.intellij.i18n.SecruxBundle
import com.securitycrux.secrux.intellij.i18n.SecruxI18nListener

class SecruxResultsToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        toolWindow.setStripeTitleProvider { SecruxBundle.message("toolwindow.results.stripeTitle") }
        toolWindow.title = SecruxBundle.message("toolwindow.results.stripeTitle")
        val panel = SecruxResultsToolWindowPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, null, false)
        val disposable = Disposer.newDisposable("SecruxResultsToolWindow")
        content.setDisposer(disposable)
        ApplicationManager.getApplication()
            .messageBus
            .connect(disposable)
            .subscribe(
                SecruxI18nListener.TOPIC,
                SecruxI18nListener {
                    toolWindow.setStripeTitleProvider { SecruxBundle.message("toolwindow.results.stripeTitle") }
                    toolWindow.title = SecruxBundle.message("toolwindow.results.stripeTitle")
                }
            )
        panel.bind(disposable)
        toolWindow.contentManager.addContent(content)
    }
}
