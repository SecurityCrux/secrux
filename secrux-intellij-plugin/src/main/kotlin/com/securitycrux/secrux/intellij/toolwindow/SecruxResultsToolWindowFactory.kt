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
        fun applyTitles() {
            toolWindow.setStripeTitleProvider { SecruxBundle.message("toolwindow.results.stripeTitle") }
            toolWindow.title = SecruxBundle.message("toolwindow.results.stripeTitle")
        }

        applyTitles()

        val disposable = Disposer.newDisposable("SecruxResultsToolWindow")

        val sinksPanel = SecruxResultsToolWindowPanel(project)
        val tasksPanel = SecruxTasksToolWindowPanel(project)
        val findingsPanel = SecruxFindingsToolWindowPanel(project)

        val sinksContent =
            ContentFactory.getInstance()
                .createContent(sinksPanel, SecruxBundle.message("toolwindow.results.tab.sinks"), false)
        val tasksContent =
            ContentFactory.getInstance()
                .createContent(tasksPanel, SecruxBundle.message("toolwindow.results.tab.tasks"), false)
        val findingsContent =
            ContentFactory.getInstance()
                .createContent(findingsPanel, SecruxBundle.message("toolwindow.results.tab.findings"), false)

        val sinksDisposable = Disposer.newDisposable(disposable, "SecruxResultsToolWindow:Sinks")
        val tasksDisposable = Disposer.newDisposable(disposable, "SecruxResultsToolWindow:Tasks")
        val findingsDisposable = Disposer.newDisposable(disposable, "SecruxResultsToolWindow:Findings")

        sinksContent.setDisposer(sinksDisposable)
        tasksContent.setDisposer(tasksDisposable)
        findingsContent.setDisposer(findingsDisposable)

        sinksPanel.bind(sinksDisposable)
        tasksPanel.bind(tasksDisposable)
        findingsPanel.bind(findingsDisposable)

        ApplicationManager.getApplication()
            .messageBus
            .connect(disposable)
            .subscribe(
                SecruxI18nListener.TOPIC,
                SecruxI18nListener {
                    applyTitles()
                    sinksContent.displayName = SecruxBundle.message("toolwindow.results.tab.sinks")
                    tasksContent.displayName = SecruxBundle.message("toolwindow.results.tab.tasks")
                    findingsContent.displayName = SecruxBundle.message("toolwindow.results.tab.findings")
                }
            )

        toolWindow.contentManager.addContent(sinksContent)
        toolWindow.contentManager.addContent(tasksContent)
        toolWindow.contentManager.addContent(findingsContent)
    }
}
