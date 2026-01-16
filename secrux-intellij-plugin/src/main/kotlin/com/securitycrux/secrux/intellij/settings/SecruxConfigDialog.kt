package com.securitycrux.secrux.intellij.settings

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBUI
import com.securitycrux.secrux.intellij.i18n.SecruxBundle
import com.securitycrux.secrux.intellij.secrux.SelectSecruxTaskDialog
import com.securitycrux.secrux.intellij.sinks.SinkCatalog
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.LayoutManager
import java.awt.Rectangle
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants
import javax.swing.Scrollable

class SecruxConfigDialog(
    private val project: Project
) : DialogWrapper(project) {

    private data class SinkCatalogComboItem(
        val id: String,
        val title: String
    ) {
        override fun toString(): String = title
    }

    private data class PointsToComboItem(
        val id: String,
        val title: String
    ) {
        override fun toString(): String = title
    }

    private val settings = SecruxProjectSettings.getInstance(project)

    private val baseUrlField = JBTextField(settings.state.baseUrl)
    private val taskIdField = JBTextField(settings.state.taskId)
    private val includeSnippetsCheckbox =
        JBCheckBox(SecruxBundle.message("label.includeSnippets"), settings.state.includeSnippetsOnReport)
    private val includeEnrichmentCheckbox =
        JBCheckBox(SecruxBundle.message("label.includeEnrichment"), settings.state.includeEnrichmentOnReport)
    private val excludedPathsRegexField = JBTextField(settings.state.excludedPathRegex).apply {
        toolTipText = SecruxBundle.message("tooltip.excludePathsRegex")
    }
    private val excludedSinkTypesField = JBTextField(settings.state.excludedSinkTypes.joinToString(",")).apply {
        toolTipText = SecruxBundle.message("tooltip.excludeSinkTypes")
    }

    private val sinkCatalogItems =
        arrayOf(
            SinkCatalogComboItem(SinkCatalog.ID_BUILTIN_ALL, SecruxBundle.message("sinkCatalog.builtinAll")),
            SinkCatalogComboItem(SinkCatalog.ID_CUSTOM_FILE, SecruxBundle.message("sinkCatalog.customFile"))
        )
    private val sinkCatalogCombo = JComboBox(sinkCatalogItems).apply {
        toolTipText = SecruxBundle.message("tooltip.sinkCatalog")
        selectedItem = sinkCatalogItems.firstOrNull { it.id == settings.state.sinkCatalogId } ?: sinkCatalogItems.first()
    }
    private val sinkCatalogFileField = JBTextField(settings.state.sinkCatalogFilePath).apply {
        toolTipText = SecruxBundle.message("tooltip.sinkCatalogFile")
        minimumSize = Dimension(JBUI.scale(260), preferredSize.height)
    }

    private val pointsToModeItems =
        arrayOf(
            PointsToComboItem("OFF", SecruxBundle.message("pointsTo.mode.off")),
            PointsToComboItem("PRECOMPUTE", SecruxBundle.message("pointsTo.mode.precompute")),
        )
    private val pointsToModeCombo = JComboBox(pointsToModeItems).apply {
        toolTipText = SecruxBundle.message("tooltip.pointsTo.mode")
        selectedItem = pointsToModeItems.firstOrNull { it.id == settings.state.pointsToIndexMode } ?: pointsToModeItems.first()
    }

    private val pointsToAbstractionItems =
        arrayOf(
            PointsToComboItem("TYPE", SecruxBundle.message("pointsTo.abstraction.type")),
            PointsToComboItem("ALLOC_SITE", SecruxBundle.message("pointsTo.abstraction.allocSite")),
        )
    private val pointsToAbstractionCombo = JComboBox(pointsToAbstractionItems).apply {
        toolTipText = SecruxBundle.message("tooltip.pointsTo.abstraction")
        selectedItem =
            pointsToAbstractionItems.firstOrNull { it.id == settings.state.pointsToAbstraction } ?: pointsToAbstractionItems.first()
    }

    private val pointsToMaxRootsField = JBTextField(settings.state.pointsToMaxRootsPerToken.toString()).apply {
        toolTipText = SecruxBundle.message("tooltip.pointsTo.maxRoots")
    }
    private val pointsToMaxBeanCandidatesField = JBTextField(settings.state.pointsToMaxBeanCandidates.toString()).apply {
        toolTipText = SecruxBundle.message("tooltip.pointsTo.maxBeanCandidates")
    }
    private val pointsToMaxCallTargetsField = JBTextField(settings.state.pointsToMaxCallTargets.toString()).apply {
        toolTipText = SecruxBundle.message("tooltip.pointsTo.maxCallTargets")
    }

    private val tokenField = JBPasswordField()
    private val tokenStatusLabel = JBLabel()

    init {
        title = SecruxBundle.message("dialog.config.title")
        init()

        refreshSinkCatalogUiState()
        sinkCatalogCombo.addActionListener { refreshSinkCatalogUiState() }

        refreshTokenStatus()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        val form =
            WidthTrackingScrollPanel(VerticalLayout(JBUI.scale(8))).apply {
                border = JBUI.Borders.empty(8)
            }

        val browseTasksButton = JButton(SecruxBundle.message("action.browseTasks")).apply {
            addActionListener { event ->
                val baseUrl = baseUrlField.text.trim()
                if (baseUrl.isBlank()) {
                    Messages.showErrorDialog(panel, SecruxBundle.message("error.baseUrlRequired"), SecruxBundle.message("dialog.title"))
                    return@addActionListener
                }

                fun openSelector(token: String) {
                    val selector = SelectSecruxTaskDialog(project = project, baseUrl = baseUrl, token = token)
                    if (!selector.showAndGet()) return
                    selector.selectedTaskId?.let { id -> taskIdField.text = id }
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
                        Messages.showErrorDialog(panel, SecruxBundle.message("error.tokenNotSet"), SecruxBundle.message("dialog.title"))
                        return@getTokenAsync
                    }
                    openSelector(token)
                }
            }
        }

        val saveTokenButton = JButton(SecruxBundle.message("action.saveToken")).apply {
            addActionListener {
                val token = String(tokenField.password).trim().ifBlank { null }
                tokenField.text = ""
                SecruxTokenStore.setTokenAsync(project, token) { refreshTokenStatus() }
            }
        }

        val clearTokenButton = JButton(SecruxBundle.message("action.clearToken")).apply {
            addActionListener {
                tokenField.text = ""
                SecruxTokenStore.setTokenAsync(project, null) { refreshTokenStatus() }
            }
        }

        form.add(JBLabel(SecruxBundle.message("config.section.server")))
        form.add(JBLabel(SecruxBundle.message("label.baseUrl")))
        form.add(baseUrlField)
        form.add(JBLabel(SecruxBundle.message("label.taskId")))
        form.add(
            JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
                add(taskIdField, BorderLayout.CENTER)
                add(browseTasksButton, BorderLayout.EAST)
            }
        )

        form.add(JBLabel(SecruxBundle.message("config.section.scan")))
        form.add(includeSnippetsCheckbox)
        form.add(includeEnrichmentCheckbox)
        form.add(JBLabel(SecruxBundle.message("label.excludePathsRegex")))
        form.add(excludedPathsRegexField)
        form.add(JBLabel(SecruxBundle.message("label.excludeSinkTypes")))
        form.add(excludedSinkTypesField)
        form.add(JBLabel(SecruxBundle.message("label.sinkCatalog")))
        form.add(sinkCatalogCombo)
        form.add(JBLabel(SecruxBundle.message("label.sinkCatalogFile")))
        form.add(sinkCatalogFileField)

        form.add(JBLabel(SecruxBundle.message("config.section.analysis")))
        form.add(JBLabel(SecruxBundle.message("label.pointsTo.mode")))
        form.add(pointsToModeCombo)
        form.add(JBLabel(SecruxBundle.message("label.pointsTo.abstraction")))
        form.add(pointsToAbstractionCombo)
        form.add(JBLabel(SecruxBundle.message("label.pointsTo.maxRoots")))
        form.add(pointsToMaxRootsField)
        form.add(JBLabel(SecruxBundle.message("label.pointsTo.maxBeanCandidates")))
        form.add(pointsToMaxBeanCandidatesField)
        form.add(JBLabel(SecruxBundle.message("label.pointsTo.maxCallTargets")))
        form.add(pointsToMaxCallTargetsField)

        form.add(JBLabel(SecruxBundle.message("config.section.auth")))
        form.add(JBLabel(SecruxBundle.message("label.accessToken")))
        form.add(tokenField)
        form.add(
            JPanel(BorderLayout()).apply {
                add(
                    JPanel().apply {
                        add(saveTokenButton)
                        add(clearTokenButton)
                    },
                    BorderLayout.WEST
                )
                add(tokenStatusLabel, BorderLayout.CENTER)
            }
        )

        panel.add(
            JBScrollPane(form).apply {
                horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            },
            BorderLayout.CENTER
        )
        return panel
    }

    override fun doOKAction() {
        settings.state.baseUrl = baseUrlField.text.trim()
        settings.state.taskId = taskIdField.text.trim()
        settings.state.includeSnippetsOnReport = includeSnippetsCheckbox.isSelected
        settings.state.includeEnrichmentOnReport = includeEnrichmentCheckbox.isSelected
        settings.state.excludedPathRegex = excludedPathsRegexField.text.trim()
        settings.state.excludedSinkTypes =
            excludedSinkTypesField.text.split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .toMutableList()
        settings.state.sinkCatalogId =
            (sinkCatalogCombo.selectedItem as? SinkCatalogComboItem)?.id ?: SinkCatalog.ID_BUILTIN_ALL
        settings.state.sinkCatalogFilePath = sinkCatalogFileField.text.trim()

        settings.state.pointsToIndexMode =
            (pointsToModeCombo.selectedItem as? PointsToComboItem)?.id ?: "OFF"
        settings.state.pointsToAbstraction =
            (pointsToAbstractionCombo.selectedItem as? PointsToComboItem)?.id ?: "TYPE"
        settings.state.pointsToMaxRootsPerToken = pointsToMaxRootsField.text.trim().toIntOrNull()?.coerceIn(1, 5_000) ?: 60
        settings.state.pointsToMaxBeanCandidates =
            pointsToMaxBeanCandidatesField.text.trim().toIntOrNull()?.coerceIn(0, 500) ?: 25
        settings.state.pointsToMaxCallTargets =
            pointsToMaxCallTargetsField.text.trim().toIntOrNull()?.coerceIn(1, 500) ?: 50

        super.doOKAction()
    }

    private fun refreshSinkCatalogUiState() {
        val selectedId = (sinkCatalogCombo.selectedItem as? SinkCatalogComboItem)?.id
        sinkCatalogFileField.isEnabled = selectedId == SinkCatalog.ID_CUSTOM_FILE
    }

    private fun refreshTokenStatus() {
        SecruxTokenStore.getTokenAsync(project) { token ->
            tokenStatusLabel.text =
                if (token != null) {
                    SecruxBundle.message("label.tokenStatus.stored")
                } else {
                    SecruxBundle.message("label.tokenStatus.notSet")
                }
        }
    }

    private class WidthTrackingScrollPanel(
        layout: LayoutManager,
    ) : JPanel(layout), Scrollable {
        override fun getPreferredScrollableViewportSize(): Dimension = preferredSize

        override fun getScrollableUnitIncrement(
            visibleRect: Rectangle,
            orientation: Int,
            direction: Int,
        ): Int = JBUI.scale(16)

        override fun getScrollableBlockIncrement(
            visibleRect: Rectangle,
            orientation: Int,
            direction: Int,
        ): Int = (visibleRect.height - JBUI.scale(16)).coerceAtLeast(JBUI.scale(16))

        override fun getScrollableTracksViewportWidth(): Boolean = true

        override fun getScrollableTracksViewportHeight(): Boolean = false
    }
}
